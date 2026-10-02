package dev.conduit.mcp.ide

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import dev.conduit.core.AgentMode
import dev.conduit.mcp.ide.tools.GetTestResultsTool
import dev.conduit.mcp.ide.tools.ListRunConfigsTool
import dev.conduit.mcp.ide.tools.RunConfigTool
import dev.conduit.mcp.ide.tools.RunTestsTool
import dev.conduit.mcp.ide.tools.TestResultsCollector

class ExecutionToolsTest : ToolTestCase() {
    fun testListingAndUnknownConfigurations() {
        assertEquals("The project has no run configurations.", call(ListRunConfigsTool).text)

        val missing = call(RunConfigTool, "name" to "Nope")
        assertTrue(missing.text, missing.isError && missing.text.contains("list_run_configs"))
        assertTrue(call(RunTestsTool).isError)
        assertTrue(call(RunTestsTool, "config" to "Nope").isError)
    }

    fun testRunningIsRefusedInModesThatDoNotExecute() {
        java("shop/ATest.java", "package shop;\n\npublic class ATest {}\n")
        mode = AgentMode.ASK
        val result = call(RunTestsTool, "file" to "shop/ATest.java")
        // Either the IDE has no way to run that file, or the policy stops it: nothing may start.
        assertTrue(result.text, result.isError)
    }

    fun testTestResultsAreReportedFromTheLastRun() {
        assertEquals("No test run has finished in this IDE session yet.", call(GetTestResultsTool).text)

        val root = SMTestProxy.SMRootTestProxy()
        root.setPresentation("CheckoutTest")
        val suite = SMTestProxy("CheckoutTest", true, null)
        root.addChild(suite)
        val passing = SMTestProxy("totalIsSummed", false, null)
        val failing = SMTestProxy("discountIsApplied", false, null)
        suite.addChild(passing)
        suite.addChild(failing)
        passing.setStarted()
        passing.setFinished()
        failing.setStarted()
        failing.setTestFailed("expected:<90> but was:<100>", "at shop.CheckoutTest.discountIsApplied(CheckoutTest.java:21)", false)
        TestResultsCollector.getInstance(project).record(root)

        val result = call(GetTestResultsTool)
        assertTrue(result.isError)
        val lines = result.text.lines()
        assertEquals("CheckoutTest: 1 passed, 1 failed", lines[0])
        assertEquals("FAILED CheckoutTest.discountIsApplied", lines[1])
        assertTrue(result.text, result.text.contains("expected:<90> but was:<100>"))
        assertTrue(result.text, result.text.contains("CheckoutTest.java:21"))

        val allGreen = SMTestProxy.SMRootTestProxy()
        allGreen.setPresentation("Green")
        val ok = SMTestProxy("works", false, null)
        allGreen.addChild(ok)
        ok.setStarted()
        ok.setFinished()
        TestResultsCollector.getInstance(project).record(allGreen)
        val green = call(GetTestResultsTool)
        assertFalse(green.isError)
        assertEquals("Green: 1 passed, 0 failed", green.text)
    }
}
