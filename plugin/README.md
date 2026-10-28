# README


## About 
DozerDb enhances Neo4j core / AKA Neo4j Community Edition with enterprise features.

This project builds the actual plugin which is used to enhance Neo4j Community Edition. 

See https://dozerdb.org for installation instructions.

## Versioning
The plugin combines the dozerdb-core and dozerdb-browser artifacts into an uber jar which is dropped into the Neo4j lib directory and takes control of the bootstrap process.

The version uses the current Neo4j full version number and appends the final number anytime the dozerdb-core or dozerdb-browser projects have an update.

Example - for Neo4j version 5.26.27 - the first release would be 5.26.27.0.
If a change occurs within the dozerdb-browser or dozerdb-core package - then the version would become:  5.26.27.1 and so on.

## Development
Please ensure you use java 17 or above when working on the plugin.

If you would like to use an open source java version manager - please check out https://sdkman.io/
For those using sdkman - you can use the following command to switch to the favor of openjdk that we use.



## Building
Ensure you have JDK 17+ first or you will get compile errors.

To build the project - you can run the following command:
```
(cd .. && scripts/maven.sh -pl plugin -am verify) 
```

Build and install Browser first using the root [build instructions](../README.md). The shared Maven wrapper lives at the repository root.
