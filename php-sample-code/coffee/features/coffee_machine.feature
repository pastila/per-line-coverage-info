Feature: Coffee Machine Operations
  In order to enjoy various coffee beverages
  As a coffee lover
  I need to be able to brew different types of coffee and manage machine supplies

  @batch_1
  Scenario: Brewing an Espresso
    Given I have a coffee machine
    When I brew an espresso
    Then the result should be "Espresso brewed successfully"

  @batch_1
  Scenario: Brewing an Americano
    Given I have a coffee machine
    When I brew an americano
    Then the result should be "Americano brewed successfully"

  @batch_1
  Scenario: Brewing a Cappuccino
    Given I have a coffee machine
    When I brew a cappuccino
    Then the result should be "Cappuccino brewed successfully"

  @batch_1
  Scenario: Brewing a Latte
    Given I have a coffee machine
    When I brew a latte
    Then the result should be "Latte brewed successfully"

  @batch_2
  Scenario: Brewing coffee when water is low
    Given I have a coffee machine with low water
    When I brew an espresso
    Then the result should be "Not enough water"

  @batch_2
  Scenario: Brewing coffee when coffee beans are low
    Given I have a coffee machine with low coffee beans
    When I brew an espresso
    Then the result should be "Not enough coffee beans"

  @batch_2
  Scenario: Brewing coffee when milk is low for cappuccino
    Given I have a coffee machine with low milk
    When I brew a cappuccino
    Then the result should be "Not enough milk"

  @batch_2
  Scenario: Brewing coffee when no cups are available
    Given I have a coffee machine with no cups
    When I brew an espresso
    Then the result should be "No cups available"

  @batch_3
  Scenario: Adding sugar to coffee
    Given I have a coffee machine
    When I add 10 grams of sugar
    Then the sugar level should increase by 10 grams

  @batch_3
  Scenario: Refilling water
    Given I have a coffee machine with low water
    When I refill 500ml of water
    Then the water level should increase by 500ml

  @batch_3
  Scenario: Refilling coffee beans
    Given I have a coffee machine with low coffee beans
    When I refill 100 grams of coffee beans
    Then the coffee beans level should increase by 100 grams

  @batch_4
  Scenario: Refilling milk
    Given I have a coffee machine with low milk
    When I refill 200ml of milk
    Then the milk level should increase by 200ml

  @batch_4
  Scenario: Refilling cups
    Given I have a coffee machine with no cups
    When I refill 5 cups
    Then the cups available should be 5