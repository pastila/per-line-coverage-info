<?php

namespace CoverageViewer\Test\Calc\Tests;

use Behat\Behat\Context\Context;
use CoverageViewer\Test\Calc\BasicCalculator;
use Exception;

/**
 * Defines application features from the specific context.
 */
class FeatureContext implements Context
{
    private $calculator;
    private $result;

    /**
     * Initializes context.
     *
     * Every scenario gets its own context instance.
     * You can also pass arbitrary arguments to the
     * context constructor through behat.yml.
     */
    public function __construct()
    {
        $this->calculator = new BasicCalculator();
    }

    /**
     * @Given I have a calculator
     */
    public function iHaveACalculator()
    {
        // Calculator is already initialized in constructor
    }

    /**
     * @When I add :a and :b
     */
    public function iAddAnd($a, $b)
    {
        $this->result = BasicCalculator::add((float)$a, (float)$b);
    }

    /**
     * @When I subtract :b from :a
     */
    public function iSubtractFrom($b, $a)
    {
        $this->result = BasicCalculator::subtract((float)$a, (float)$b);
    }

    /**
     * @When I multiply :a and :b
     */
    public function iMultiplyAnd($a, $b)
    {
        $this->result = BasicCalculator::multiply((float)$a, (float)$b);
    }

    /**
     * @When I divide :a by :b
     */
    public function iDivideBy($a, $b)
    {
        $this->result = BasicCalculator::divide((float)$a, (float)$b);
    }

    /**
     * @Then the result should be :expected
     */
    public function theResultShouldBe($expected)
    {
        if ((float)$expected != $this->result) {
            throw new Exception("Expected " . $expected . ", but got " . $this->result);
        }
    }
}