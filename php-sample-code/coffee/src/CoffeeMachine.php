<?php
namespace CoverageViewer\Test\Coffee;

class CoffeeMachine
{
    private $waterLevel;
    private $coffeeBeans;
    private $milkLevel;
    private $sugarLevel;
    private $cupsAvailable;

    public function __construct()
    {
        $this->waterLevel = 1000; // ml
        $this->coffeeBeans = 500; // grams
        $this->milkLevel = 500; // ml
        $this->sugarLevel = 300; // grams
        $this->cupsAvailable = 10;
    }

    public function brewEspresso()
    {
        if ($this->waterLevel < 30) {
            return 'Not enough water';
        }
        if ($this->coffeeBeans < 7) {
            return 'Not enough coffee beans';
        }
        if ($this->cupsAvailable < 1) {
            return 'No cups available';
        }

        $this->waterLevel -= 30;
        $this->coffeeBeans -= 7;
        $this->cupsAvailable -= 1;

        return 'Espresso brewed successfully';
    }

    public function brewAmericano()
    {
        if ($this->waterLevel < 180) {
            return 'Not enough water';
        }
        if ($this->coffeeBeans < 7) {
            return 'Not enough coffee beans';
        }
        if ($this->cupsAvailable < 1) {
            return 'No cups available';
        }

        $this->waterLevel -= 180;
        $this->coffeeBeans -= 7;
        $this->cupsAvailable -= 1;

        return 'Americano brewed successfully';
    }

    public function brewCappuccino()
    {
        if ($this->waterLevel < 30) {
            return 'Not enough water';
        }
        if ($this->coffeeBeans < 7) {
            return 'Not enough coffee beans!';
        }
        if ($this->milkLevel < 150) {
            return 'Not enough milk';
        }
        if ($this->cupsAvailable < 1) {
            return 'No cups available';
        }

        $this->waterLevel -= 30;
        $this->coffeeBeans -= 7;
        $this->milkLevel -= 150;
        $this->cupsAvailable -= 1;

        return 'Cappuccino brewed successfully';
    }

    public function brewLatte()
    {
        if ($this->waterLevel < 30) {
            return 'Not enough water';
        }
        if ($this->coffeeBeans < 7) {
            return 'Not enough coffee beans';
        }
        if ($this->milkLevel < 200) {
            return 'Not enough milk';
        }
        if ($this->cupsAvailable < 1) {
            return 'No cups available';
        }

        $this->waterLevel -= 30;
        $this->coffeeBeans -= 7;
        $this->milkLevel -= 200;
        $this->cupsAvailable -= 1;

        return 'Latte brewed successfully';
    }

    public function addSugar($grams)
    {
        if ($grams <= 0) {
            return 'Invalid sugar amount';
        }
        $this->sugarLevel += $grams;
        return "Added {$grams}g of sugar";
    }

    public function getWaterLevel()
    {
        return $this->waterLevel;
    }

    public function getCoffeeBeans()
    {
        return $this->coffeeBeans;
    }

    public function getMilkLevel()
    {
        return $this->milkLevel;
    }

    public function getSugarLevel()
    {
        return $this->sugarLevel;
    }

    public function getCupsAvailable()
    {
        return $this->cupsAvailable;
    }

    public function refillWater($ml)
    {
        if ($ml <= 0) {
            return 'Invalid water amount';
        }
        $this->waterLevel += $ml;
        return "Refilled water by {$ml}ml";
    }

    public function refillCoffeeBeans($grams)
    {
        if ($grams <= 0) {
            return 'Invalid coffee beans amount';
        }
        $this->coffeeBeans += $grams;
        return "Refilled coffee beans by {$grams}g";
    }

    public function refillMilk($ml)
    {
        if ($ml <= 0) {
            return 'Invalid milk amount';
        }
        $this->milkLevel += $ml;
        return "Refilled milk by {$ml}ml";
    }

    public function refillCups($count)
    {
        if ($count <= 0) {
            return 'Invalid cup count';
        }
        $this->cupsAvailable += $count;
        return "Refilled cups by {$count}";
    }

    public function uncoveredFunction()
    {
        echo "this function is uncovered by tests";
    }
}
