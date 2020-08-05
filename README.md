# lambda-parent

## Usage

The intended usage of this module is as a Maven parent for any AWS Lambda function handlers. To use it simply add
the following to your Lambda function handler `pom.xml`:

```xml
<parent>
    <groupId>com.brcolow</groupId>
    <artifactId>lambda-parent</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <relativePath>../lambda-parent</relativePath>
</parent>
```

Note: You may need to adjust the relative path to suit your setup, the curent one is intended to work with a monorepo
setup where all projects share the same root directory (the monorepo root directory).

## What It Does

Once this module as added as a parent then deploying the Lambda function handler to AWS is as easy as running `mvn deploy`.

## How It Works

We use `gmaven-plus-plugin` to run the `DeployLambda.groovy` script - it's that simple.

## Note

This is not intended for public consumption as is because it makes a bunch of assumptions that only work for my
particular needs but could be easily adapted/extended.