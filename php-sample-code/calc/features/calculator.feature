Feature: Basic Calculator Operations
  In order to perform basic calculations
  As a user
  I need to be able to add, subtract, multiply, and divide numbers

  @batch_1
  Scenario Outline: Adding two numbers
    Given I have a calculator
    When I add <a> and <b>
    Then the result should be <result>

    Examples:
      | a   | b   | result |
      | 1   | 2   | 3.0    |
      | 1.0 | 2.0 | 3.0    |
      | 0   | 2.0 | 2.0    |
      | 2.0 | 0   | 2.0    |
      | -4  | 2.0 | -2.0   |

  @batch_2
  Scenario Outline: Subtracting two numbers
    Given I have a calculator
    When I subtract <b> from <a>
    Then the result should be <result>

    Examples:
      | a   | b   | result |
      | 1   | 2   | -1.0   |
      | 2   | 1   | 1.0    |
      | 1.0 | 2.0 | -1.0   |
      | 0   | 2.0 | -2.0   |
      | 2.0 | 0   | 2.0    |
      | -4  | 2.0 | -6.0   |

  @batch_3
  Scenario Outline: Multiplying two numbers
    Given I have a calculator
    When I multiply <a> and <b>
    Then the result should be <result>

    Examples:
      | a   | b   | result |
      | 1   | 2   | 2.0    |
      | 1.0 | 2.0 | 2.0    |
      | 0   | 2.0 | 0.0    |
      | 2.0 | 0   | 0.0    |
      | -4  | 2.0 | -8.0   |

  @batch_4
  Scenario Outline: Dividing two numbers
    Given I have a calculator
    When I divide <a> by <b>
    Then the result should be <result>

    Examples:
      | a   | b   | result |
      | 1   | 2   | 0.5    |
      | 1.0 | 2.0 | 0.5    |
      | 0   | 2.0 | 0.0    |
      | -4  | 2.0 | -2.0   |