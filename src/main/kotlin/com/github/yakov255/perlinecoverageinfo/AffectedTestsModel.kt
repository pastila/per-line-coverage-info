package com.github.yakov255.perlinecoverageinfo

/**
 * Pure (Swing-free) data model for the "affected tests" view in [AffectedTestsPanel].
 *
 * Holds the immutable per-file → tests map plus mutable user state ([checkedFiles] and
 * [removedTests]) and exposes:
 * - [displayedTests]: tests visible after applying the current checks/removals.
 * - [deltaForFiles]: how many tests would disappear if a given subtree of files were unchecked.
 *
 * All recompute logic lives here so it can be unit-tested without touching the UI.
 */
class AffectedTestsModel(
    val perFile: Map<String, Set<String>>,
) {
    /** Reverse index: test → set of files that cover it. Built once in init. */
    val reverseIndex: Map<String, Set<String>>

    val allFiles: Set<String> = perFile.keys

    /** Currently checked files. Mutable. Starts equal to [allFiles]. */
    private val checkedFilesMut: MutableSet<String> = perFile.keys.toMutableSet()
    val checkedFiles: Set<String> get() = checkedFilesMut

    /** Tests the user manually removed. Mutable. */
    private val removedTestsMut: MutableSet<String> = mutableSetOf()
    val removedTests: Set<String> get() = removedTestsMut

    init {
        val rev = mutableMapOf<String, MutableSet<String>>()
        for ((file, tests) in perFile) {
            for (t in tests) {
                rev.getOrPut(t) { mutableSetOf() } += file
            }
        }
        reverseIndex = rev
    }

    /** Tests that should be visible right now: union over checked files minus removed. */
    val displayedTests: Set<String>
        get() {
            val result = linkedSetOf<String>()
            for (f in checkedFilesMut) {
                val tests = perFile[f] ?: continue
                for (t in tests) {
                    if (t !in removedTestsMut) result += t
                }
            }
            return result
        }

    fun setChecked(file: String, checked: Boolean) {
        if (file !in perFile) return
        if (checked) checkedFilesMut += file else checkedFilesMut -= file
    }

    fun setCheckedAll(files: Iterable<String>, checked: Boolean) {
        for (f in files) setChecked(f, checked)
    }

    fun checkAll() {
        checkedFilesMut.clear()
        checkedFilesMut.addAll(allFiles)
    }

    fun uncheckAll() {
        checkedFilesMut.clear()
    }

    fun removeTest(test: String) {
        removedTestsMut += test
    }

    fun removeTests(tests: Iterable<String>) {
        removedTestsMut.addAll(tests)
    }

    /**
     * Returns the number of currently-displayed tests that would disappear if every file in
     * [subtreeFiles] were unchecked. Used for the per-node Δ rendering in the tree.
     *
     * A test "disappears" iff every checked file covering it is in [subtreeFiles] —
     * i.e. no other checked file outside the subtree still covers it.
     */
    fun deltaForFiles(subtreeFiles: Set<String>): Int {
        if (subtreeFiles.isEmpty()) return 0
        var count = 0
        for (t in displayedTests) {
            val covering = reverseIndex[t] ?: continue
            // covering ∩ checkedFiles ⊆ subtreeFiles ?
            var allInSubtree = true
            var anyChecked = false
            for (f in covering) {
                if (f in checkedFilesMut) {
                    anyChecked = true
                    if (f !in subtreeFiles) {
                        allInSubtree = false
                        break
                    }
                }
            }
            if (anyChecked && allInSubtree) count++
        }
        return count
    }

    /** Total tests across all files (ignoring checks/removals). */
    val totalTests: Int
        get() {
            val s = linkedSetOf<String>()
            for ((_, tests) in perFile) s.addAll(tests)
            return s.size
        }
}
