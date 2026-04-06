<?php

declare(strict_types=1);

namespace DVDoug\Behat\CodeCoverage\Report;

use SebastianBergmann\CodeCoverage\CodeCoverage;
use SebastianBergmann\CodeCoverage\Node\File;

final readonly class PerLineCoverageReportBinary
{
    private const MAGIC = 0x434F5654; // "COVT" in ASCII
    private const VERSION = 1;
    private const MARKER_FILE = 0x46494C45; // "FILE"
    private const MARKER_END = 0x454E4446; // "ENDF"

    private const BUFFER_SIZE = 65536; // 64KB buffer

    /**
     * Process coverage data and write binary format to stream
     *
     * @param CodeCoverage $coverage The coverage data
     * @param resource $stream Output stream (file handle, php://output, etc.)
     * @param string $pathToCoverageRoot Root path for the repository
     */
    public function processToStream(
        CodeCoverage $coverage,
        $stream,
        string $pathToCoverageRoot
    ): void {
        $report = $coverage->getReport();

        // First pass: collect all test identifiers from coverage data
        $testIndexMap = [];
        $testIndex = 0;

        foreach ($report as $item) {
            if (! $item instanceof File) {
                continue;
            }

            $lineCoverageData = $item->lineCoverageData();
            if (! $lineCoverageData) {
                continue;
            }

            foreach ($lineCoverageData as $testData) {
                if ($testData === null) {
                    continue;
                }

                foreach ($testData as $testIdentifier) {
                    if (! isset($testIndexMap[$testIdentifier])) {
                        $testIndexMap[$testIdentifier] = $testIndex++;
                    }
                }
            }
        }

        // Convert testIndexMap to ordered array
        asort($testIndexMap);
        $tests = array_keys($testIndexMap);

        // Initialize buffer
        $buffer = '';

        // Write header
        $this->writeHeader($stream, $buffer);

        // Write test section
        $this->writeTestSection($stream, $tests, $buffer, $pathToCoverageRoot);

        // Second pass: write file coverage incrementally
        foreach ($report as $item) {
            if (! $item instanceof File) {
                continue;
            }

            $filePath = $item->pathAsString();
            $relativeFilePath = str_replace(rtrim($pathToCoverageRoot, '/') . DIRECTORY_SEPARATOR, '', $filePath);

            $lineCoverageData = $item->lineCoverageData();

            $this->writeFileCoverage($stream, $relativeFilePath, $lineCoverageData, $testIndexMap, $buffer);
        }

        // Write end marker
        $this->writeUint32ToBuffer($stream, self::MARKER_END, $buffer);

        // Flush any remaining data in buffer
        $this->flushBuffer($stream, $buffer);
    }

    /**
     * Write binary header
     */
    private function writeHeader($stream, string &$buffer): void
    {
        // Magic number
        $this->writeUint32ToBuffer($stream, self::MAGIC, $buffer);

        // Version
        $this->writeUint32ToBuffer($stream, self::VERSION, $buffer);

        // Flags (reserved, set to 0)
        $this->writeUint32ToBuffer($stream, 0, $buffer);
    }

    /**
     * Write test section
     */
    private function writeTestSection($stream, array $tests, string &$buffer, string $pathToCoverageRoot): void
    {
        // Total test count
        $this->writeUint32ToBuffer($stream, count($tests), $buffer);

        $repoRootPrefix = rtrim($pathToCoverageRoot, '/') . DIRECTORY_SEPARATOR;

        // Write each test
        foreach ($tests as $test) {
            $this->writeStringToBuffer($stream, substr($test, strlen($repoRootPrefix)), $buffer);
        }
    }

    /**
     * Write file coverage data with test set deduplication
     */
    private function writeFileCoverage($stream, string $filePath, array $lineCoverageData, array $testIndexMap, string &$buffer): void
    {
        // Build line coverage with test indices
        $lines = [];

        foreach ($lineCoverageData as $lineNumber => $testData) {
            $testIndexes = [];

            if ($testData !== null) {
                foreach ($testData as $testIdentifier) {
                    if (isset($testIndexMap[$testIdentifier])) {
                        $currentTestIndex = $testIndexMap[$testIdentifier];
                        $testIndexes[$currentTestIndex] = true;
                    }
                }
            }

            if (! empty($testIndexes)) {
                $lines[(int)$lineNumber] = array_keys($testIndexes);
            } else {
                $lines[(int)$lineNumber] = []; // Empty array for coverable but not covered
            }
        }

        // Deduplicate test sets for this file
        $testSets = [];
        $setMap = []; // test set signature -> set index

        // Special empty set
        $emptySetKey = '[]';
        $setMap[$emptySetKey] = -1;

        $lineSetMap = []; // line number -> set index

        foreach ($lines as $lineNumber => $indices) {
            // Create signature for this test set
            if (empty($indices)) {
                $setKey = $emptySetKey;
            } else {
                $sorted = $indices;
                sort($sorted);
                $setKey = '[' . implode(',', $sorted) . ']';
            }

            // Find or create set
            if (! isset($setMap[$setKey])) {
                $setIndex = count($testSets);
                $setMap[$setKey] = $setIndex;
                $testSets[] = $sorted ?? [];
            }

            $lineSetMap[$lineNumber] = $setMap[$setKey];
        }

        // Write file marker
        $this->writeUint32ToBuffer($stream, self::MARKER_FILE, $buffer);

        // Write file path
        $this->writeStringToBuffer($stream, $filePath, $buffer);

        // Write test sets for this file
        $this->writeUint32ToBuffer($stream, count($testSets), $buffer);

        foreach ($testSets as $set) {
            $this->writeUint32ToBuffer($stream, count($set), $buffer);
            // Write all test indices in one batch for better performance
            if (! empty($set)) {
                $this->writeUint32BatchToBuffer($stream, $set, $buffer);
            }
        }

        // Write line coverage
        ksort($lineSetMap); // Sort by line number
        $this->writeUint32ToBuffer($stream, count($lineSetMap), $buffer);

        // Prepare batch data for lines
        $lineBatch = [];
        foreach ($lineSetMap as $lineNumber => $setIndex) {
            $lineBatch[] = $lineNumber;
            $lineBatch[] = $setIndex; // int32, but we store as uint32
        }

        // Write all lines in one batch
        if (! empty($lineBatch)) {
            $this->writeUint32BatchToBuffer($stream, $lineBatch, $buffer);
        }
    }

    /**
     * Write a 32-bit unsigned integer to buffer
     */
    private function writeUint32ToBuffer($stream, int $value, string &$buffer): void
    {
        $buffer .= pack('V', $value);
        $this->flushIfNeeded($stream, $buffer);
    }

    /**
     * Write a string with length prefix to buffer
     */
    private function writeStringToBuffer($stream, string $str, string &$buffer): void
    {
        $buffer .= pack('V', strlen($str));
        $buffer .= $str;
        $this->flushIfNeeded($stream, $buffer);
    }

    /**
     * Write multiple integers in one batch to buffer
     */
    private function writeUint32BatchToBuffer($stream, array $values, string &$buffer): void
    {
        $format = str_repeat('V', count($values));
        $buffer .= pack($format, ...$values);
        $this->flushIfNeeded($stream, $buffer);
    }

    /**
     * Flush buffer to stream if it exceeds buffer size
     */
    private function flushIfNeeded($stream, string &$buffer): void
    {
        if (strlen($buffer) >= self::BUFFER_SIZE) {
            $this->flushBuffer($stream, $buffer);
        }
    }

    /**
     * Flush buffer to stream
     */
    private function flushBuffer($stream, string &$buffer): void
    {
        if ($buffer !== '') {
            fwrite($stream, $buffer);
            $buffer = '';
        }
    }

    /**
     * Process coverage data and write to file (backward compatibility)
     */
    public function process(
        CodeCoverage $coverage,
        string $target,
        string $pathToCoverageRoot
    ): void {
        // Open file for writing
        $stream = fopen($target, 'wb');
        if ($stream === false) {
            throw new \RuntimeException("Failed to open file for writing: $target");
        }

        try {
            $this->processToStream(
                $coverage,
                $stream,
                $pathToCoverageRoot
            );
        } finally {
            fclose($stream);
        }
    }
}