# INTENT

This is port of C# Ballistic Calculator engine (see ../BallisticCalculator.Net for original project
sources).

The calculator uses step-by-step simulation using 3 degree of freedom model and using pretabulated drag tables to calculate drag.

The goal of this project is to copy functionality from C# to Java.


# STRUCTURE

The project is build using maven. 

The project is organized as following:

 * `src/main` contains the source of the calculator
 * `src/test` contains the test sources

For tests the calculator uses junit.

For keeping physical data (distance, velocity, energy, weight and so on), the calculator use javax.measure.Quantity package. However, because (unlike C# version) the Quantity is highly  inefficient implementation, inside the calculations all data is converted to default unit: distance in foot, velocity in foot per second, weight in grain and so on.
