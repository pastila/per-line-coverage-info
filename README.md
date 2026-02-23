# Per-Line Coverage Info

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](https://github.com/your-repo/per-line-coverage-info/actions)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

<!-- Plugin description -->
Per-Line Coverage Info is an IntelliJ IDEA plugin that integrates with external coverage APIs to display real-time per-line code coverage information directly in the editor. It provides visual indicators for covered and uncovered lines, enhancing code quality analysis.
<!-- Plugin description end -->

## Table of Contents
- [Overview](#overview)
- [Features](#features)
- [API Specification](#api-specification)
- [Settings Configuration](#settings-configuration)
- [Usage](#usage)
- [Installation](#installation)
- [Development](#development)

## Overview

The Per-Line Coverage Info plugin fetches coverage data from a custom API and overlays it onto your code in the IntelliJ editor. This allows developers to see which lines of code have been executed during testing without running full coverage suites locally.

**Supported Languages:** Primarily designed for PHP, with potential support for other languages via API extensibility.

**Integration:** Seamlessly works with IntelliJ's built-in Coverage tool window, providing a unified view of coverage data.

## Features

- **Custom API Coverage Data Fetching:** Pulls detailed coverage information from external services.
- **Bearer Token Authentication:** Securely authenticates with APIs using bearer tokens.
- **Per-Line Coverage Display:** Shows coverage status for each line with gutter icons and editor highlighting.
- **IntelliJ Coverage Tool Window Integration:** Displays coverage summaries and navigates to uncovered lines.

## API Specification

The plugin expects coverage data in JSON format via a GET request to a configurable endpoint.

### Response Format
```json
{
  "files": {
    "/path/to/file.php": {
      "lines": {
        "10": {"covered": true, "hits": 5},
        "15": {"covered": false, "hits": 0}
      }
    },
    "/path/to/another.php": {
      "lines": {
        "5": {"covered": true, "hits": 1}
      }
    }
  }
}
```

- `files`: An object where keys are file paths and values are coverage objects.
- `lines`: An object where keys are line numbers (strings) and values are coverage objects.
- `covered`: Boolean indicating if the line was executed.
- `hits`: Integer count of execution hits (optional, for detailed reporting).

### Authentication
Requests include a Bearer token in the Authorization header: `Authorization: Bearer <token>`.

Example cURL request:
```bash
curl -X GET https://api.example.com/coverage \
  -H "Authorization: Bearer your-token"
```

## Settings Configuration

Access plugin settings via **File > Settings > Tools > Coverage API** (or **IntelliJ IDEA > Preferences > Tools > Coverage API** on macOS).

### Fields
- **API URL:** The endpoint for fetching coverage data (e.g., `https://api.example.com/coverage`).
- **Bearer Token:** Your authentication token for the API.

### Default Values
- API URL: (empty)
- Bearer Token: (empty)

*Screenshots: The settings dialog shows input fields for API URL and Bearer Token, with placeholders and validation hints.*

## Usage

1. Configure the API URL and Bearer Token in settings.
2. Open a PHP file in the editor.
3. Coverage data loads automatically, displaying gutter icons (green check for covered, red X for uncovered).
4. Use the Coverage tool window (**View > Tool Windows > Coverage**) to view summaries and navigate to specific lines.
5. Editor highlights: Covered lines may have a subtle green background, uncovered lines a red tint.

*Screenshots: Editor view with gutter icons and highlighted lines; Coverage tool window showing file coverage percentages.*

## Installation

1. Download the plugin JAR from the [releases page](https://github.com/your-repo/per-line-coverage-info/releases).
2. In IntelliJ IDEA, go to **File > Settings > Plugins**.
3. Click the gear icon > **Install Plugin from Disk**.
4. Select the downloaded JAR and restart IntelliJ.

### Requirements
- IntelliJ IDEA 2021.3 or later
- PHP plugin installed (for PHP language support)

## Development

### Building and Running
1. Clone the repository: `git clone https://github.com/your-repo/per-line-coverage-info.git`
2. Open in IntelliJ IDEA.
3. Run `./gradlew build` to build the plugin.
4. For development, use `./gradlew runIde` to launch a test instance.

### Contributing Guidelines
- Fork the repository and create a feature branch.
- Follow Kotlin coding standards.
- Submit a pull request with a clear description of changes.

### License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
