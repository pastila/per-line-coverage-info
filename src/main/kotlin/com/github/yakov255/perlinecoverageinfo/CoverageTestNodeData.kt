package com.github.yakov255.perlinecoverageinfo

internal sealed class TestNodeData(val displayName: String) {
    /** Intermediate directory or namespace node (used in tree view). */
    class Dir(displayName: String, val count: Int = 0) : TestNodeData(displayName)
    class BehatGroup(val featurePath: String, displayName: String = featurePath) : TestNodeData(displayName)
    class BehatScenario(val label: String, val originalTestName: String) : TestNodeData(label)
    class PhpUnitGroup(val className: String, displayName: String = className) : TestNodeData(displayName)
    class PhpUnitMethod(val methodName: String, val fullTestName: String) : TestNodeData(methodName)
}

/** Node payloads for the affected-files pane (CheckboxTree). */
internal sealed class FileNodeData {
    /** Directory subtree node. [files] is the set of file paths under this directory. */
    class Dir(val displayName: String, val files: Set<String>) : FileNodeData()

    /** Leaf file node. */
    class FileEntry(val path: String, val displayName: String) : FileNodeData()
}
