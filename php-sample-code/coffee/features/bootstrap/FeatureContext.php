<?php

namespace CoverageViewer\Test\Coffee\Tests;

use Behat\Behat\Context\Context;
use CoverageViewer\Test\Coffee\CoffeeMachine;
use Exception;
use ReflectionClass;

/**
 * Defines application features from the specific context.
 */
class FeatureContext implements Context
{
    private $coffeeMachine;
    private $result;
    private $initialWaterLevel;
    private $initialCoffeeBeans;
    private $initialMilkLevel;
    private $initialSugarLevel;
    private $initialCupsAvailable;

    /**
     * Initializes context.
     *
     * Every scenario gets its own context instance.
     * You can also pass arbitrary arguments to the
     * context constructor through behat.yml.
     */
    public function __construct()
    {
        $this->coffeeMachine = new CoffeeMachine();
    }

    /**
     * @Given I have a coffee machine
     */
    public function iHaveACoffeeMachine()
    {
        // Coffee machine is already initialized in constructor
        $this->initialWaterLevel = $this->coffeeMachine->getWaterLevel();
        $this->initialCoffeeBeans = $this->coffeeMachine->getCoffeeBeans();
        $this->initialMilkLevel = $this->coffeeMachine->getMilkLevel();
        $this->initialSugarLevel = $this->coffeeMachine->getSugarLevel();
        $this->initialCupsAvailable = $this->coffeeMachine->getCupsAvailable();
    }

    /**
     * @Given I have a coffee machine with low water
     */
    public function iHaveACoffeeMachineWithLowWater()
    {
        $this->coffeeMachine = new CoffeeMachine();
        // Set water level to below minimum required for espresso (30ml)
        $reflection = new ReflectionClass($this->coffeeMachine);
        $property = $reflection->getProperty('waterLevel');
        $property->setAccessible(true);
        $property->setValue($this->coffeeMachine, 10);
        $this->initialWaterLevel = $this->coffeeMachine->getWaterLevel();
    }

    /**
     * @Given I have a coffee machine with low coffee beans
     */
    public function iHaveACoffeeMachineWithLowCoffeeBeans()
    {
        $this->coffeeMachine = new CoffeeMachine();
        // Set coffee beans to below minimum required for espresso (7g)
        $reflection = new ReflectionClass($this->coffeeMachine);
        $property = $reflection->getProperty('coffeeBeans');
        $property->setAccessible(true);
        $property->setValue($this->coffeeMachine, 3);
        $this->initialCoffeeBeans = $this->coffeeMachine->getCoffeeBeans();
    }

    /**
     * @Given I have a coffee machine with low milk
     */
    public function iHaveACoffeeMachineWithLowMilk()
    {
        $this->coffeeMachine = new CoffeeMachine();
        // Set milk level to below minimum required for cappuccino (150ml)
        $reflection = new ReflectionClass($this->coffeeMachine);
        $property = $reflection->getProperty('milkLevel');
        $property->setAccessible(true);
        $property->setValue($this->coffeeMachine, 50);
        $this->initialMilkLevel = $this->coffeeMachine->getMilkLevel();
    }

    /**
     * @Given I have a coffee machine with no cups
     */
    public function iHaveACoffeeMachineWithNoCups()
    {
        $this->coffeeMachine = new CoffeeMachine();
        // Set cups to 0
        $reflection = new ReflectionClass($this->coffeeMachine);
        $property = $reflection->getProperty('cupsAvailable');
        $property->setAccessible(true);
        $property->setValue($this->coffeeMachine, 0);
        $this->initialCupsAvailable = $this->coffeeMachine->getCupsAvailable();
    }

    /**
     * @When I brew an espresso
     */
    public function iBrewAnEspresso()
    {
        $this->result = $this->coffeeMachine->brewEspresso();
    }

    /**
     * @When I brew an americano
     */
    public function iBrewAnAmericano()
    {
        $this->result = $this->coffeeMachine->brewAmericano();
    }

    /**
     * @When I brew a cappuccino
     */
    public function iBrewACappuccino()
    {
        $this->result = $this->coffeeMachine->brewCappuccino();
    }

    /**
     * @When I brew a latte
     */
    public function iBrewALatte()
    {
        $this->result = $this->coffeeMachine->brewLatte();
    }

    /**
     * @When I add :grams grams of sugar
     */
    public function iAddGramsOfSugar($grams)
    {
        $this->result = $this->coffeeMachine->addSugar((int)$grams);
        $this->initialSugarLevel = $this->coffeeMachine->getSugarLevel() - (int)$grams;
    }

    /**
     * @When I refill 500ml of water
     */
    public function iRefill500MlOfWater()
    {
        $this->result = $this->coffeeMachine->refillWater(500);
    }

    /**
     * @When I refill :grams grams of coffee beans
     */
    public function iRefillGramsOfCoffeeBeans($grams)
    {
        $this->result = $this->coffeeMachine->refillCoffeeBeans((int)$grams);
    }

    /**
     * @When I refill 200ml of milk
     */
    public function iRefill200MlOfMilk()
    {
        $this->result = $this->coffeeMachine->refillMilk(200);
    }

    /**
     * @When I refill :count cups
     */
    public function iRefillCups($count)
    {
        $this->result = $this->coffeeMachine->refillCups((int)$count);
    }

    /**
     * @Then the result should be :expected
     */
    public function theResultShouldBe($expected)
    {
        if ($expected !== $this->result) {
            throw new Exception("Expected '" . $expected . "', but got '" . $this->result . "'");
        }
    }

    /**
     * @Then the sugar level should increase by :grams grams
     */
    public function theSugarLevelShouldIncreaseByGrams($grams)
    {
        $currentSugarLevel = $this->coffeeMachine->getSugarLevel();
        $expectedSugarLevel = $this->initialSugarLevel + (int)$grams;
        
        if ($currentSugarLevel != $expectedSugarLevel) {
            throw new Exception("Expected sugar level to be " . $expectedSugarLevel . "g, but got " . $currentSugarLevel . "g");
        }
    }

    /**
     * @Then the water level should increase by 500ml
     */
    public function theWaterLevelShouldIncreaseBy500Ml()
    {
        $currentWaterLevel = $this->coffeeMachine->getWaterLevel();
        $expectedWaterLevel = $this->initialWaterLevel + 500;

        if ($currentWaterLevel != $expectedWaterLevel) {
            throw new Exception("Expected water level to be " . $expectedWaterLevel . "ml, but got " . $currentWaterLevel . "ml");
        }
    }

    /**
     * @Then the coffee beans level should increase by :grams grams
     */
    public function theCoffeeBeansLevelShouldIncreaseByGrams($grams)
    {
        $currentCoffeeBeans = $this->coffeeMachine->getCoffeeBeans();
        $expectedCoffeeBeans = $this->initialCoffeeBeans + (int)$grams;
        
        if ($currentCoffeeBeans != $expectedCoffeeBeans) {
            throw new Exception("Expected coffee beans level to be " . $expectedCoffeeBeans . "g, but got " . $currentCoffeeBeans . "g");
        }
    }

    /**
     * @Then the milk level should increase by 200ml
     */
    public function theMilkLevelShouldIncreaseBy200Ml()
    {
        $currentMilkLevel = $this->coffeeMachine->getMilkLevel();
        $expectedMilkLevel = $this->initialMilkLevel + 200;

        if ($currentMilkLevel != $expectedMilkLevel) {
            throw new Exception("Expected milk level to be " . $expectedMilkLevel . "ml, but got " . $currentMilkLevel . "ml");
        }
    }

    /**
     * @Then the cups available should be :count
     */
    public function theCupsAvailableShouldBe($count)
    {
        $currentCups = $this->coffeeMachine->getCupsAvailable();
        
        if ($currentCups != (int)$count) {
            throw new Exception("Expected cups available to be " . $count . ", but got " . $currentCups);
        }
    }
}