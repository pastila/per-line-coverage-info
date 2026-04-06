# PHP Code Coverage Example with Behat and Codecov

This repository demonstrates how to integrate Codecov with PHP projects using Behat for behavior-driven development (BDD) testing and code coverage reporting. The project contains two separate PHP applications that showcase different testing scenarios.

## Project Structure

The repository contains two independent PHP applications:

### 1. Calculator Application (`calc/`)
- **Source**: [`calc/src/BasicCalculator.php`](calc/src/BasicCalculator.php)
- **Features**: [`calc/features/calculator.feature`](calc/features/calculator.feature)
- **Tests**: [`calc/features/bootstrap/FeatureContext.php`](calc/features/bootstrap/FeatureContext.php)

**Features:**
- Basic arithmetic operations (add, subtract, multiply, divide)
- Advanced operations (modulus, power, square)
- Error handling for division by zero

### 2. Coffee Machine Application (`coffee/`)
- **Source**: [`coffee/src/CoffeeMachine.php`](coffee/src/CoffeeMachine.php)
- **Features**: [`coffee/features/coffee_machine.feature`](coffee/features/coffee_machine.feature)
- **Tests**: [`coffee/features/bootstrap/FeatureContext.php`](coffee/features/bootstrap/FeatureContext.php)

**Features:**
- Multiple coffee types (Espresso, Americano, Cappuccino, Latte)
- Resource management (water, coffee beans, milk, sugar, cups)
- Refill operations for all resources
- Error handling for insufficient resources

## Technology Stack

- **PHP 8.2** with Xdebug and PCOV for code coverage
- **Behat 3.26** for BDD testing
- **dvdoug/behat-code-coverage** for coverage reports
- **Docker** for containerized development environment
- **GitLab CI** for continuous integration
- **Codecov** for coverage reporting

## Setup and Installation

### Prerequisites
- Docker and Docker Compose
- Make utility

### Available Make Commands

The project includes a [`Makefile`](Makefile) with convenient commands for development:

- **`make install`** - Install dependencies for both applications
- **`make test`** - Run all tests for both applications
- **`make behat`** - Run Behat tests for both applications (same as `make test`)

### Quick Start

1. **Install dependencies:**
   ```bash
   make install
   ```

2. **Run all tests:**
   ```bash
   make test
   ```

### Manual Setup

1. **Build and start the Docker environment:**
   ```bash
   ./dude build
   ./dude up -d
   ```

2. **Install dependencies for each application:**
   ```bash
   ./dude run bash -c 'cd calc && composer install'
   ./dude run bash -c 'cd coffee && composer install'
   ```

3. **Run tests for each application:**
   ```bash
   ./dude run bash -c 'cd calc && vendor/bin/behat'
   ./dude run bash -c 'cd coffee && vendor/bin/behat'
   ```

## Code Coverage Configuration

Both applications are configured to generate code coverage reports using the `dvdoug/behat-code-coverage` extension:

- **Coverage Format**: Clover XML (`coverage.clover.xml`)
- **Coverage Provider**: PCOV (with Xdebug fallback)
- **Report Location**: Generated in each application's root directory

### Configuration Files
- [`calc/behat.yml`](calc/behat.yml) - Calculator test configuration
- [`coffee/behat.yml`](coffee/behat.yml) - Coffee machine test configuration

## Continuous Integration

The project uses GitLab CI with two parallel jobs:

### Calculator Job
- Runs Behat tests for the calculator application
- Generates coverage report
- Uploads to Codecov

### Coffee Machine Job
- Runs Behat tests for the coffee machine application
- Generates coverage report
- Uploads to Codecov

### CI Configuration
See [`.gitlab-ci.yml`](.gitlab-ci.yml) for the complete CI/CD pipeline configuration.

## Development

### Running Individual Test Suites

**Calculator tests:**
```bash
./dude run bash -c 'cd calc && vendor/bin/behat'
```

**Coffee machine tests:**
```bash
./dude run bash -c 'cd coffee && vendor/bin/behat'
```

### Viewing Coverage Reports

After running tests, coverage reports are available in:
- `calc/coverage.clover.xml` - Calculator coverage
- `coffee/coverage.clover.xml` - Coffee machine coverage

## Features and Scenarios

### Calculator Features
- Basic arithmetic operations with various number combinations
- Error handling for division by zero
- Support for both integers and floating-point numbers

### Coffee Machine Features
- Brewing different coffee types (Espresso, Americano, Cappuccino, Latte)
- Resource management and validation
- Refill operations for all machine components
- Error scenarios for insufficient resources

Some change