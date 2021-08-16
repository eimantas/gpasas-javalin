# Prerequisites

- JDK 11
- Maven
- Apple developer certificate

## Build & Run

0) Add your Apple developer certificate (*.p12 file) to `/src/main/resources/cert/` and edit `/src/main/java/gpasas/Main.java` to specify path and password

1) Build:
```shell
mvn clean package
```

2) Run:
```shell
java -jar ./target/gpasas-jar-with-dependencies.jar
```

3) [Open In Browser](http://localhost:8080)