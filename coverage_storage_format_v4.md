# Binary Coverage Storage Format (Version 4 - COV4)

## Overview

COV4 is the fourth-generation binary format for storing code coverage data in the Coverage Viewer system. It extends the COV3 format with pre-computed directory coverage statistics for fast directory queries.

## Motivation

The COV4 format was designed to address performance limitations in directory coverage queries:

1. **O(N) directory aggregation**: COV3 required aggregating all files in a directory to compute coverage
2. **Slow directory queries**: Each directory query required scanning all files in that directory
3. **No hierarchical statistics**: Parent directory coverage wasn't pre-computed

COV4 provides:
- Pre-computed directory coverage statistics
- O(1) directory coverage lookup instead of O(N) aggregation
- Hierarchical directory coverage (parent directories include child coverage)
- Backward compatibility with COV3 format
- Minimal storage overhead (~40 bytes per directory)

## File Structure

```
COV4 File Structure:
┌─────────────────────────────┐
│         HEADER              │ (28 bytes)
│   - Magic: "COV3" (0x434F5633) │
│   - Version: 4              │
│   - File count (F)          │
│   - Test count (T)          │
│   - Directory count (D)     │ ← NEW in version 4
│   - Index offset            │
│   - Directory offset        │ ← NEW in version 4
└─────────────────────────────┘
┌─────────────────────────────┐
│      GLOBAL TEST LIST       │
│   For each test (0..T-1):   │
│     - Test ID length (L)    │
│     - Test ID (UTF-8)       │
└─────────────────────────────┘
┌─────────────────────────────┐
│      FILE SECTIONS          │
│   For each file (0..F-1):   │
│     ┌─────────────────────┐ │
│     │  FILE HEADER        │ │
│     │  - Marker: "FILE"   │ │
│     │  - Path length (P)  │ │
│     │  - Path (UTF-8)     │ │
│     │  - Test set count (S)│ │
│     └─────────────────────┘ │
│     ┌─────────────────────┐ │
│     │  TEST SETS          │ │
│     │  For each set (0..S-1):│
│     │    - Set size (K)   │ │
│     │    - Test indices   │ │
│     │      (K × uint32)   │ │
│     └─────────────────────┘ │
│     ┌─────────────────────┐ │
│     │  LINE COVERAGE      │ │
│     │  - Line count (N)   │ │
│     │  For each line:     │ │
│     │    - Line number    │ │
│     │    - Set index      │ │
│     │      (int32, -1 for │ │
│     │       uncovered)    │ │
│     └─────────────────────┘ │
└─────────────────────────────┘
┌─────────────────────────────┐
│      FILE INDEX             │
│   - Marker: "INDX" (0x494E4458) │
│   - Entry count (F)        │
│   For each entry (0..F-1): │
│     - Path hash (uint64)   │
│     - Path offset (uint32) │
│     - Data offset (uint32) │
│     - Data size (uint32)   │
│     - CRC32 (uint32)       │
└─────────────────────────────┘
┌─────────────────────────────┐
│   DIRECTORY COVERAGE        │ ← NEW in version 4
│   - Marker: "DIRC" (0x44495243) │
│   - Entry count (D)        │
│   For each entry (0..D-1): │
│     - Path length (P)      │
│     - Path (UTF-8)        │
│     - Covered lines (uint32)│
│     - Uncovered lines (uint32)│
│     - Total coverable (uint32)│
└─────────────────────────────┘
┌─────────────────────────────┐
│         TRAILER             │
│   - Marker: "ENDF" (0x454E4446) │
│   - Overall CRC32          │
└─────────────────────────────┘
```

## Header Format (28 bytes)

| Offset | Size | Type     | Description                       |
|--------|------|----------|-----------------------------------|
| 0      | 4    | uint32   | Magic number: 0x434F5633 ("COV3") |
| 4      | 4    | uint32   | Format version: 4                 |
| 8      | 4    | uint32   | Number of files (F)               |
| 12     | 4    | uint32   | Number of tests (T)               |
| 16     | 4    | uint32   | Number of directories (D)         |
| 20     | 4    | uint32   | File index offset                 |
| 24     | 4    | uint32   | Directory section offset          |

## Directory Coverage Section

The directory coverage section contains pre-computed coverage statistics for all directories in the codebase. This enables O(1) lookup of directory coverage instead of O(N) file aggregation.

### Directory Entry Format

| Field | Size | Type | Description |
|-------|------|------|-------------|
| Path length | 4 | uint32 | Length of directory path in bytes |
| Path | P | UTF-8 | Directory path with trailing slash (e.g., "src/app/") |
| Covered lines | 4 | uint32 | Number of lines with test coverage (set index ≥ 0) |
| Uncovered lines | 4 | uint32 | Number of lines without coverage (set index = -1) |
| Total coverable | 4 | uint32 | Covered + Uncovered lines |

### Directory Path Convention

- **Trailing slashes**: All directory paths end with `/` (e.g., `src/app/`)
- **Root directory**: Represented as empty string `""`
- **Hierarchical coverage**: Parent directories include coverage from all child directories
  - Example: `src/app/utils/math.go` contributes to `src/app/utils/`, `src/app/`, and `src/`

### Directory Coverage Calculation

Directory coverage is calculated as:
1. For each file, count covered (set index ≥ 0) and uncovered (set index = -1) lines
2. Add file coverage to its immediate directory
3. Propagate coverage up to all parent directories
4. Store results in directory coverage map

Example:
- File `src/app/main.go`: 3 covered, 2 uncovered
- File `src/app/utils/math.go`: 2 covered, 1 uncovered
- File `src/lib/helper.go`: 1 covered, 2 uncovered

Directory coverage:
- `src/`: 6 covered, 5 uncovered (sum of all files)
- `src/app/`: 5 covered, 3 uncovered (main.go + utils/math.go)
- `src/app/utils/`: 2 covered, 1 uncovered (only utils/math.go)
- `src/lib/`: 1 covered, 2 uncovered (only helper.go)

## Data Structures

### DirectoryCoverage Struct
```go
type DirectoryCoverage struct {
    Path           string // Directory path with trailing slash
    Covered        uint32 // Lines with set index ≥ 0
    Uncovered      uint32 // Lines with set index = -1  
    TotalCoverable uint32 // Covered + Uncovered
}
```

### COV3CoverageData Struct (Updated)
```go
type COV3CoverageData struct {
    Tests      []string
    Files      map[string]*OptimizedFileCoverage
    FileIndex  []FileIndexEntry
    FileInfos  map[string]*COV3FileInfo
    Directories map[string]*DirectoryCoverage // NEW in version 4
    TotalFiles uint32
    TotalLines uint32
    Coverage   float64
}
```

## API Integration

### Fast Directory Queries
The COV4 format enables fast directory coverage queries through the `/api/coverage-tree` endpoint:

```go
// O(1) directory lookup using pre-computed directory coverage
func buildDirectoryTree(cov3Data *COV3CoverageData, pathFilter string, depth int) map[string]interface{} {
    // Use pre-computed directory coverage for O(1) lookup
    for dirPath, dirCoverage := range cov3Data.Directories {
        // Fast directory coverage access
        covered := dirCoverage.Covered
        uncovered := dirCoverage.Uncovered
        total := dirCoverage.TotalCoverable
    }
}
```

### Performance Comparison
- **COV3 (without directory section)**: O(N) file aggregation per directory query
- **COV4 (with directory section)**: O(1) lookup from pre-computed directory map

## Migration

### Automatic Migration
The system automatically migrates text coverage data to COV4 binary format on first access.

### Manual Migration
Use the migration tool to convert existing COV3 files to COV4 format:
```bash
go run migrate_cov3_to_cov4.go <coverage-file-or-directory>
```

## Backward Compatibility

### Reading COV4 Files
- Version 4 readers can read COV3 files (fall back to calculating directory coverage)
- Version 3 readers cannot read COV4 files (version mismatch error)

### Writing COV4 Files
- All new coverage data is written in COV4 format
- Existing COV3 files are upgraded to COV4 when modified

## Storage Overhead

Directory coverage adds minimal storage overhead:
- ~40 bytes per directory entry
- Typical project: 100 directories = ~4KB overhead
- Benefit: Eliminates O(N) file aggregation for directory queries

## Implementation Details

### Key Functions
- `CalculateDirectoryCoverage()`: Computes hierarchical directory coverage from files
- `WriteCOV3CoverageFile()`: Writes COV4 format with directory section
- `ReadCOV3CoverageFile()`: Reads COV4 format with directory section
- `LoadCoverageData()`: Returns COV3CoverageData with pre-computed directories

### Constants
```go
const (
    COV3FormatMagic   = 0x434F5633 // "COV3"
    COV3FormatVersion = 4          // Updated from 3 to 4
    COV3MarkerFile    = 0x46494C45 // "FILE"
    COV3MarkerEnd     = 0x454E4446 // "ENDF"
    COV3MarkerIndex   = 0x494E4458 // "INDX"
    COV3MarkerDir     = 0x44495243 // "DIRC" (NEW)
)
```

## Related Documentation
- [COVT Format](coverage_transmission_format_v1.md) - Transmission format
