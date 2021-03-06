package org.elm.ide.typing

import com.intellij.lang.SmartEnterProcessorWithFixers
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import org.elm.ide.inspections.MissingCaseBranchAdder
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.ElmTypes.*
import org.elm.lang.core.psi.elements.*

class ElmSmartEnterProcessor : SmartEnterProcessorWithFixers() {
    init {
        addFixers(
                CaseBranchFixer(),
                CaseExpressionFixer(),
                LetInFixer(),
                IfElseFixer(),
                FunctionBodyFixer()
        )
        addEnterProcessors(ElmEnterProcessor())
    }

    // All of the fixers will be called with the returned element. Then this function will be called
    // again, and the return value passed to the enter processor. This means that the function needs
    // to return the same element both times. If any fixer modified the document, then process
    // repeats. Fixers need to make sure they're idempotent, or they can create an infinite loop. If
    // that happens, the processor will stop calling this function and discard any changes already
    // made.
    override fun getStatementAtCaret(editor: Editor?, psiFile: PsiFile?): PsiElement? {
        val statement = super.getStatementAtCaret(editor, psiFile)
        if (statement == null || statement is PsiWhiteSpace) return null

        // Identifiers don't parse as value declarations unless an `=` is present so we need to
        // check for that case separately.
        if (elementIsValueDeclWithoutEquals(statement)) {
            return statement
        }

        return statement.ancestors
                .takeWhile { it !is ElmFile }
                .find {
                    it is ElmCaseOfBranch
                            || it is ElmCaseOfExpr
                            || it is ElmLetInExpr
                            || it is ElmIfElseExpr
                            || it is ElmValueDeclaration
                }
    }
}

private class ElmEnterProcessor : SmartEnterProcessorWithFixers.FixEnterProcessor() {
    override fun doEnter(atCaret: PsiElement, file: PsiFile, editor: Editor, modified: Boolean): Boolean {
        if (modified && atCaret is ElmCaseOfExpr && atCaret.branches.isNotEmpty()) {
            val branch = atCaret.branches.first()
            indentAfterElement(editor, branch, branch)
        } else if (modified && atCaret is ElmLetInExpr) {
            val inKeyword = atCaret.inKeyword ?: return true
            val indentedElement = (inKeyword.parent as? PsiErrorElement ?: inKeyword)
            val anchor = if (atCaret.valueDeclarationList.isEmpty()) atCaret.letKeyword else inKeyword
            indentAfterElement(editor, anchor, indentedElement)
        } else if (modified && atCaret is ElmCaseOfBranch && atCaret.arrow != null) {
            indentAfterElement(editor, atCaret.arrow!!, atCaret)
        } else if (modified && atCaret is ElmIfElseExpr && atCaret.thenKeywords.isNotEmpty() && atCaret.elseKeywords.isNotEmpty()) {
            val anchor = if (atCaret.expressionList.size > atCaret.thenKeywords.size) {
                atCaret.elseKeywords.last()
            } else {
                atCaret.thenKeywords.last()
            }
            indentAfterElement(editor, anchor, atCaret.elseKeywords.last())
        } else if (modified && atCaret is ElmValueDeclaration && atCaret.eqElement != null) {
            indentAfterElement(editor, atCaret.eqElement!!, atCaret)
        } else {
            plainEnter(editor)
        }
        return true
    }

    private fun indentAfterElement(editor: Editor, anchor: PsiElement, indentedElement: PsiElement) {
        val extraIndent = 5 // \n + 4 spaces
        val indentLen = if (indentedElement.isTopLevel) 0 else indentedElement.prevSibling.textLength
        moveAfterElement(editor, anchor, indentLen + extraIndent)
    }

    private fun moveAfterElement(editor: Editor, anchor: PsiElement, offset: Int) {
        editor.caretModel.moveToOffset(anchor.textRange.endOffset + offset)
    }
}

private class CaseExpressionFixer : SmartEnterProcessorWithFixers.Fixer<ElmSmartEnterProcessor>() {
    override fun apply(editor: Editor, processor: ElmSmartEnterProcessor, element: PsiElement) {
        if (element !is ElmCaseOfExpr || element.branches.isNotEmpty()) return
        MissingCaseBranchAdder(element).addMissingBranches()
    }
}

private class LetInFixer : SmartEnterProcessorWithFixers.Fixer<ElmSmartEnterProcessor>() {
    override fun apply(editor: Editor, processor: ElmSmartEnterProcessor, element: PsiElement) {
        if (element !is ElmLetInExpr || element.inKeyword != null) return
        val indent = guessIndent(element)
        val lastDecl = element.valueDeclarationList.lastOrNull()
        val anchor = lastDecl ?: element.letKeyword
        val endOffset = anchor.textRange.endOffset
        val emptyLine = if (lastDecl == null) "\n$indent    " else ""

        editor.document.insertString(endOffset,
                "$emptyLine\n${indent}in\n$indent    ")
    }
}

private class CaseBranchFixer : SmartEnterProcessorWithFixers.Fixer<ElmSmartEnterProcessor>() {
    override fun apply(editor: Editor, processor: ElmSmartEnterProcessor, element: PsiElement) {
        if (element !is ElmCaseOfBranch || element.arrow != null) return
        val indent = guessIndent(element)
        val endOffset = element.pattern.textRange.endOffset
        editor.document.insertString(endOffset, " ->\n$indent    ")
    }
}

private class IfElseFixer : SmartEnterProcessorWithFixers.Fixer<ElmSmartEnterProcessor>() {
    override fun apply(editor: Editor, processor: ElmSmartEnterProcessor, element: PsiElement) {
        if (element !is ElmIfElseExpr) return
        val thenKeywords = element.thenKeywords
        val elseKeywords = element.elseKeywords
        if (elseKeywords.size > thenKeywords.size ||
                elseKeywords.isNotEmpty() && elseKeywords.size == thenKeywords.size) return
        val expressionList = element.expressionList
        val expression = expressionList.lastOrNull() ?: return

        // chained `if` expressions aren't parsed as a group if they're missing anything before the final else
        val elementPrev = element.prevSiblings.withoutWs.firstOrNull()
        val indentOffset = when {
            elementPrev?.elementType == ElmTypes.ELSE -> -1
            else -> 0
        }

        val indent = guessIndent(element, indentOffset)

        val exprPrev = expression.prevSiblings.withoutWs.first()
        val exprNext = expression.nextSiblings.withoutWs.firstOrNull()

        val (thenString, endExpr) = when {
            // `then` keyword present, cursor on predicate or `then`
            (exprPrev.elementType == ElmTypes.THEN && expressionList.size % 2 == 1)
                    || exprNext?.elementType == ElmTypes.THEN -> {
                "\n$indent    " to exprNext!!
            }
            // `then` keyword not present
            exprPrev.elementType == ElmTypes.IF -> {
                " then\n$indent    " to expression
            }
            // cursor on body
            else -> "" to expression
        }

        val s = thenString + "\n${indent}else\n$indent    "
        val endOffset = endExpr.textRange.endOffset

        editor.document.insertString(endOffset, s)
    }
}

private class FunctionBodyFixer : SmartEnterProcessorWithFixers.Fixer<ElmSmartEnterProcessor>() {
    override fun apply(editor: Editor, processor: ElmSmartEnterProcessor, element: PsiElement) {
        if (!elementIsValueDeclWithoutEquals(element)) {
            return
        }
        val indent = guessIndent(element)
        editor.document.insertString(element.textRange.endOffset, " =\n$indent    ")
    }
}


private fun elementIsValueDeclWithoutEquals(element: PsiElement): Boolean {
    return element.elementType in listOf(LOWER_CASE_IDENTIFIER, RIGHT_BRACE, RIGHT_PARENTHESIS) &&
            (element.isTopLevel || element.parent is ElmLetInExpr)
}
