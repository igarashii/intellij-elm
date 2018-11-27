package org.frawa.elmtest.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.execution.testframework.sm.runner.events.*;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.frawa.elmtest.core.ElmTestJsonProcessor.*;
import static org.frawa.elmtest.core.LabelUtils.EMPTY_PATH;
import static org.frawa.elmtest.core.LabelUtils.toPath;
import static org.junit.Assert.*;

public class ElmTestJsonProcessorTest {

    private ElmTestJsonProcessor processor = new ElmTestJsonProcessor();

    @Test
    public void junk() {
        List<TreeNodeEvent> list = processor.accept("junk");
        assertNull(list);
    }

    @Test
    public void runStart() {
        List<TreeNodeEvent> list = processor.accept("{\"event\":\"runStart\",\"testCount\":\"9\",\"fuzzRuns\":\"100\",\"paths\":[],\"initialSeed\":\"1448022641\"}\n");
        assertTrue(list.isEmpty());
    }

    @Test
    public void runComplete() {
        List<TreeNodeEvent> list = processor.accept("{\"event\":\"runComplete\",\"passed\":\"8\",\"failed\":\"1\",\"duration\":\"353\",\"autoFail\":null}\n");
        assertTrue(list.isEmpty());
    }

    @Test
    public void testCompleted() {
        List<TreeNodeEvent> list = processor.accept("{\"event\":\"testCompleted\",\"status\":\"pass\",\"labels\":[\"Module\",\"test\"],\"failures\":[],\"duration\":\"1\"}");
        assertEquals(3, list.size());
        assertTrue(list.get(0) instanceof TestSuiteStartedEvent);
        assertTrue(list.get(1) instanceof TestStartedEvent);
        assertTrue(list.get(2) instanceof TestFinishedEvent);
        assertEquals("Module", list.get(0).getName());
        assertEquals("test", list.get(1).getName());
        assertEquals("test", list.get(2).getName());
    }

    @Test
    public void testCompletedWithSlashes() {
        List<TreeNodeEvent> list = processor.accept("{\"event\":\"testCompleted\",\"status\":\"pass\",\"labels\":[\"Module\",\"test / stuff\"],\"failures\":[],\"duration\":\"1\"}");
        assertEquals(3, list.size());
        assertTrue(list.get(0) instanceof TestSuiteStartedEvent);
        assertTrue(list.get(1) instanceof TestStartedEvent);
        assertTrue(list.get(2) instanceof TestFinishedEvent);
        assertEquals("Module", list.get(0).getName());
        assertEquals("test / stuff", list.get(1).getName());
        assertEquals("test / stuff", list.get(2).getName());
    }

    @Test
    public void toPath1() {
        JsonObject obj = new Gson().fromJson("{\"labels\":[\"Module\",\"test\"]}", JsonObject.class);
        Path path = ElmTestJsonProcessor.toPath(obj);
        assertEquals(2, path.getNameCount());
        assertEquals("Module", path.getName(0).toString());
        assertEquals("test", path.getName(1).toString());
    }

    @Test
    public void toPathWithSlashes() {
        JsonObject obj = new Gson().fromJson("{\"labels\":[\"Module\",\"test / stuff\"]}", JsonObject.class);
        Path path = ElmTestJsonProcessor.toPath(obj);
        assertEquals(2, path.getNameCount());
        assertEquals("Module", path.getName(0).toString());
        assertEquals("test+%2F+stuff", path.getName(1).toString());
    }

    @Test
    public void closeNoSuites() {
        Path from = toPath(Arrays.asList("Module", "suite", "test"));
        Path to = toPath(Arrays.asList("Module", "suite", "test2"));
        List<Path> paths = closeSuitePaths(from, to).collect(Collectors.toList());
        assertEquals(Arrays.asList(), paths);
    }

    @Test
    public void closeOneSuite() {
        Path from = toPath(Arrays.asList("Module", "suite", "test"));
        Path to = toPath(Arrays.asList("Module", "suite2", "test2"));
        List<String> paths = closeSuitePaths(from, to).map(Path::toString).collect(Collectors.toList());
        assertEquals(Arrays.asList("Module/suite"), paths);
    }

    @Test
    public void closeTwoSuites() {
        Path from = toPath(Arrays.asList("Module", "suite", "deep", "test"));
        Path to = toPath(Arrays.asList("Module", "suite2", "test2"));
        List<String> paths = closeSuitePaths(from, to).map(Path::toString).collect(Collectors.toList());
        assertEquals(Arrays.asList("Module/suite/deep", "Module/suite"), paths);
    }

    @Test
    public void closeInitialSuite() {
        Path from = EMPTY_PATH;
        Path to = toPath(Arrays.asList("Module", "suite", "test"));
        List<String> paths = closeSuitePaths(from, to).map(Path::toString).collect(Collectors.toList());
        assertEquals(Arrays.asList(), paths);
    }

    @Test
    public void openNoSuites() {
        Path from = toPath(Arrays.asList("Module", "suite", "test"));
        Path to = toPath(Arrays.asList("Module", "suite", "test2"));
        List<String> paths = openSuitePaths(from, to).map(Path::toString).collect(Collectors.toList());
        assertEquals(Arrays.asList(), paths);
    }

    @Test
    public void openOneSuite() {
        Path from = toPath(Arrays.asList("Module", "suite", "test"));
        Path to = toPath(Arrays.asList("Module", "suite2", "test2"));
        List<String> paths = openSuitePaths(from, to).map(Path::toString).collect(Collectors.toList());
        assertEquals(Arrays.asList("Module/suite2"), paths);
    }

    @Test
    public void openTwoSuites() {
        Path from = toPath(Arrays.asList("Module", "suite", "test"));
        Path to = toPath(Arrays.asList("Module", "suite2", "deep2", "test2"));
        List<String> paths = openSuitePaths(from, to).map(Path::toString).collect(Collectors.toList());
        assertEquals(Arrays.asList("Module/suite2", "Module/suite2/deep2"), paths);
    }

    @Test
    public void openInitialSuites() {
        Path from = EMPTY_PATH;
        Path to = toPath(Arrays.asList("Module", "suite", "test"));
        List<String> paths = openSuitePaths(from, to).map(Path::toString).collect(Collectors.toList());
        assertEquals(Arrays.asList("Module", "Module/suite"), paths);
    }

    @Test
    public void openSuiteWithSlash() {
        Path from = toPath(Arrays.asList("Module"));
        Path to = toPath(Arrays.asList("Module", "suite / stuff", "test"));
        List<String> paths = openSuitePaths(from, to).map(Path::toString).collect(Collectors.toList());
        assertEquals(Arrays.asList("Module/suite+%2F+stuff"), paths);
    }

    @Test
    public void todo() {
        String text = "{\"event\":\"testCompleted\",\"status\":\"todo\",\"labels\":[\"Exploratory\",\"describe\"],\"failures\":[\"TODO comment\"],\"duration\":\"2\"}";
        JsonObject obj = getObject(text);
        assertEquals("TODO comment", getComment(obj));
        assertEquals(null, getMessage(obj));
        assertEquals(null, getExpected(obj));
        assertEquals(null, getActual(obj));
    }

    @Test
    public void fail() {
        String text = "{\"event\":\"testCompleted\",\"status\":\"fail\",\"labels\":[\"Exploratory\",\"describe\",\"fail\"],\"failures\":[{\"given\":null,\"message\":\"boom\",\"reason\":{\"type\":\"custom\",\"data\":\"boom\"}}],\"duration\":\"1\"}";
        JsonObject obj = getObject(text);
        assertEquals(null, getComment(obj));
        assertEquals("boom", getMessage(obj));
        assertEquals(null, getExpected(obj));
        assertEquals(null, getActual(obj));
    }

    @Test
    public void failEqual() {
        String text = "{\"event\":\"testCompleted\",\"status\":\"fail\",\"labels\":[\"Exploratory\",\"describe\",\"duplicate nested\",\"ok1\"],\"failures\":[{\"given\":null,\"message\":\"Expect.equal\",\"reason\":{\"type\":\"custom\",\"data\":{\"expected\":\"\\\"value\\\"\",\"actual\":\"\\\"value2\\\"\",\"comparison\":\"Expect.equal\"}}}],\"duration\":\"2\"}";
        JsonObject obj = getObject(text);
        assertEquals(null, getComment(obj));
        assertEquals("Expect.equal", getMessage(obj));
        assertEquals("\"value\"", getExpected(obj));
        assertEquals("\"value2\"", getActual(obj));
    }

    @Test
    public void failHtml() {
        String text = "{\"event\":\"testCompleted\",\"status\":\"fail\",\"labels\":[\"Exploratory\",\"Html tests 1\",\"... fails\"],\"failures\":[{\"given\":null,\"message\":\"▼ Query.fromHtml\\n\\n    <div class=\\\"container\\\">\\n        <button>\\n            I'm a button!\\n        </button>\\n    </div>\\n\\n\\n▼ Query.find [ tag \\\"button1\\\" ]\\n\\n0 matches found for this query.\\n\\n\\n✗ Query.find always expects to find 1 element, but it found 0 instead.\",\"reason\":{\"type\":\"custom\",\"data\":\"▼ Query.fromHtml\\n\\n    <div class=\\\"container\\\">\\n        <button>\\n            I'm a button!\\n        </button>\\n    </div>\\n\\n\\n▼ Query.find [ tag \\\"button1\\\" ]\\n\\n0 matches found for this query.\\n\\n\\n✗ Query.find always expects to find 1 element, but it found 0 instead.\"}}],\"duration\":\"15\"}";
        JsonObject obj = getObject(text);
        assertEquals(null, getComment(obj));
        assertTrue(getMessage(obj).contains("I'm a button!"));
        assertEquals(null, getExpected(obj));
        assertEquals(null, getActual(obj));
    }

    @Test
    public void failEqualLists() {
        String text = "{\"event\":\"testCompleted\",\"status\":\"fail\",\"labels\":[\"Deep.Exploratory\",\"Variuous Fails\",\"equalLists\"],\"failures\":[{\"given\":null,\"message\":\"Expect.equalLists\",\"reason\":{\"type\":\"custom\",\"data\":{\"expected\":[\"\\\"one\\\"\",\"\\\"expected\\\"\"],\"actual\":[\"\\\"one\\\"\",\"\\\"actual\\\"\"]}}}],\"duration\":\"1\"}\n";
        JsonObject obj = getObject(text);
        assertEquals(null, getComment(obj));
        assertEquals("Expect.equalLists", getMessage(obj));
        assertEquals("[\n" +
                "  \"\\\"one\\\"\",\n" +
                "  \"\\\"expected\\\"\"\n" +
                "]", getExpected(obj));
        assertEquals("[\n" +
                "  \"\\\"one\\\"\",\n" +
                "  \"\\\"actual\\\"\"\n" +
                "]", getActual(obj));
    }

    @Test
    public void failFallback() {
        String text = "{\"event\":\"testCompleted\",\"status\":\"fail\",\"labels\":[\"Module\",\"Fails\"],\"failures\":[{\"unknown\": \"format\"}],\"duration\":\"1\"}\n";
        JsonObject obj = getObject(text);
        Path path = ElmTestJsonProcessor.toPath(obj);

        List<TreeNodeEvent> list = testEvents(path, obj).collect(Collectors.toList());

        assertEquals(2, list.size());
        assertTrue(list.get(1) instanceof TestFailedEvent);
        assertEquals("[\n" +
                "  {\n" +
                "    \"unknown\": \"format\"\n" +
                "  }\n" +
                "]", ((TestFailedEvent) list.get(1)).getLocalizedFailureMessage());
    }

    private JsonObject getObject(String text) {
        return new Gson().fromJson(text, JsonObject.class);
    }

    @Test
    public void testCompletedWithLocation() {
        List<TreeNodeEvent> list = processor.accept("{\"event\":\"testCompleted\",\"status\":\"pass\",\"labels\":[\"Module\",\"test\"],\"failures\":[],\"duration\":\"1\"}");
        assertEquals(3, list.size());
        assertTrue(list.get(0) instanceof TestSuiteStartedEvent);
        assertTrue(list.get(1) instanceof TestStartedEvent);
        assertTrue(list.get(2) instanceof TestFinishedEvent);
        assertEquals("elmTestDescribe://Module", ((TestSuiteStartedEvent) list.get(0)).getLocationUrl());
        assertEquals("elmTestTest://Module/test", ((TestStartedEvent) list.get(1)).getLocationUrl());
    }

    @Test
    public void testCompletedWithLocationNested() {
        List<TreeNodeEvent> list = processor.accept("{\"event\":\"testCompleted\",\"status\":\"pass\",\"labels\":[\"Nested.Module\",\"test\"],\"failures\":[],\"duration\":\"1\"}");
        assertEquals(3, list.size());
        assertTrue(list.get(0) instanceof TestSuiteStartedEvent);
        assertTrue(list.get(1) instanceof TestStartedEvent);
        assertTrue(list.get(2) instanceof TestFinishedEvent);
        assertEquals("elmTestDescribe://Nested.Module", ((TestSuiteStartedEvent) list.get(0)).getLocationUrl());
        assertEquals("elmTestTest://Nested.Module/test", ((TestStartedEvent) list.get(1)).getLocationUrl());
    }

    @Test
    public void testCompletedFailedWithLocation() {
        List<TreeNodeEvent> list = processor.accept("{\"event\":\"testCompleted\",\"status\":\"fail\",\"labels\":[\"Exploratory\",\"describe\",\"fail\"],\"failures\":[{\"given\":null,\"message\":\"boom\",\"reason\":{\"type\":\"custom\",\"data\":\"boom\"}}],\"duration\":\"1\"}");
        assertEquals(4, list.size());
        assertTrue(list.get(0) instanceof TestSuiteStartedEvent);
        assertTrue(list.get(1) instanceof TestSuiteStartedEvent);
        assertTrue(list.get(2) instanceof TestStartedEvent);
        assertTrue(list.get(3) instanceof TestFailedEvent);
        assertEquals("elmTestDescribe://Exploratory", ((TestSuiteStartedEvent) list.get(0)).getLocationUrl());
        assertEquals("elmTestDescribe://Exploratory/describe", ((TestSuiteStartedEvent) list.get(1)).getLocationUrl());
        assertEquals("elmTestTest://Exploratory/describe/fail", ((TestStartedEvent) list.get(2)).getLocationUrl());
    }
}