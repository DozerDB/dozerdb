# README


## About 
DozerDb enhances Neo4j core / AKA Neo4j Community Edition with enterprise features.

This project contains the plugin's core features which are responsible for bootstrapping into Neo4j Community Edition. 

The `plugin` module in this repository combines core and the enhanced Browser into the single shaded plugin JAR.

See https://dozerdb.org for installation instructions.



## Development

Build and verification commands are in the [building guide](../docs/building.md).

Please ensure you use Java 21 or newer when working on this module; Neo4j 2026.x targets JVM 21.

If you would like to use an open source java version manager - please check out https://sdkman.io/
For those using sdkman - you can use the following command to switch to the favor of openjdk that we use.


### Code Formatting

Spotless is used to ensure uniform formatting. 

If you are building locally for development, you can use the following command to skip the spotless checks.

```
(cd .. && scripts/maven.sh -pl core -am verify) -Dspotless.check.skip -Dspotless.apply.skip
```
 
## Spotless Errors

If you get any spotless errors - you can always run the following command which will format the code
properly.  Please ensure that your code has been formatted properly before submitting a pull request.

```
(cd .. && scripts/maven.sh -pl core spotless:apply)
```

## Building
Ensure you have JDK 21 or newer first or you will get compile errors.

To build the project - you can run the following command:
```
(cd .. && scripts/maven.sh -pl core -am verify) 
```
