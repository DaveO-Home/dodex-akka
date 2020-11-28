# doDex-akka, a scala asynchronous/reactive client to doDex-vertx supporting the Cassandra nosql database for Dodex, Dodex-input and Dodex-mess

## Install Assumptions

1. Java 8 or higher installed with JAVA_HOME set.
2. Scala 2.13 and `sbt` installed.
3. npm javascript package manager installed.
4. Cassandra and or `cqlsh` (development machine may not have Cassandra installed).

## Motivation and Summary

A maiden voyage into the Scala, Akka and Cassandra world. The Akka client attaches to the Vertx Event Bus Bridge using TCP. Daniel Stieger's project @ <https://github.com/danielstieger/javaxbus> is used to communicate with the event bus on the Vertx Server. On the Akka side this code was converted to Scala and used to fomulate the message. The development/testing environment uses an embedded Cassandra database, so no install is required. However, an installed Cassendra can be used by changing the configuration in ``application.conf``. The asynchronous/reactive code is much like Vertx using Futures and Promises. Conclusion, Akka is a good place to start if you want to learn Scala and the Actor/Behavior pattern. The need for a Dodex database is simple so Cassandra usage barely scratches the surface.

__Note;__ doDex-Akka was developed on a Windows-10 machine using the ``openSUSE-leap-15-1`` WSL under VS-Code with Java11 installed on `SUSE`. The project should work in any IDE of choice starting with Java8.

## Getting Started

1. `npm install dodex-akka` or `git clone`/download from <https://github.com/DaveO-Home/dodex-akka>. If you use npm install, move node_modules/dodex-akka to an appropriate directory.

2. Edit `src/main/resources/application.conf` and change the following;
    * modify `cassandra-service` host values to reflect a networked Cassandra
    * modify ``event.bus.dev.host`` to reflect the location of the development Vertx micro-service
    * modify `event.bus.host` to reflect the location of the production Vertx micro-service
    * also change the `port` values if test and production are running on the same machine simultaneously. Make sure the `bridge.port` value in `application-conf.json` for the Vertx micro-service corresponds to these values.

3. `cd <install directory>/dodex-akka` and execute `sbt run`. This should install Scala dependencies and startup the micro-service in development mode against the default embedded Cassandra database. Review instructions below on Akka development.

4. On the Vertx side, make sure dodex-vertx is running with `Cassandra` database set.
    * Method 1; `export DEFAULT_DB=cassandra` or `Set DEFAULT_DB=cassandra` before starting the `Dodex-Vertx` micro-service.
    * Method 2; change `defaultdb` to `cassandra` in `database_config.json` file before starting vertx. 

5. With both Vertx and Akka sevices running, execute url `http://localhost:8087/test` in a browser. To test that the `Akka` service is working, follow instructions for `Dodex-Mess`. If the message box displays `connected` you are good to go. __Note;__ The Vertx service is started with `Cassandra` if the startup message `TCP Event Bus Bridge Started` is displayed.
6. You can also run `http://localhost:8087/test/bootstrap.html` for a bootstrap example.
7. Follow instructions for dodex at <https://www.npmjs.com/package/dodex-mess> and <https://www.npmjs.com/package/dodex-input>.

### Operation

1. Starting in Dev Mode to test implementation; execute `sbt run`. The `dodex-vertx` service should be running first, however if `dodex-akka` is started first, the Akka service will continue attempting the TCP handshake for a limited number of trys. This can be configured in `Limits.scala`. Conversely, if `dodex-vertx` is shutdown, the Akka client will continue with attempts to reconnect a limited number of times, frequency can also be configured.

2. Starting in Dev Mode to develop; execute `sbt` to start `sbt shell`
    * execute `set fork in run := true`, this allows the embedded `Cassandra` database to terminate when shutting down with `ctrl-c`
    * execute `set run / javaOptions += "-Ddev=true"` for forked JVM and then execute `~run` to start
    * modify some `Akka` code and execute `ctrl-c`, while still in the `sbt shell` and using `~run` the `Akka` service will restart.

3. Building a Production distribution - Review the `sbt` plugin documentation for details
    * Try `sbt stage` this will build a production setup without packaging.  You can execute by running `target/universal/stage/bin/akka-dodex-scala`. Make sure your production database is running.
    * Execute `sbt universal:packageBin` to package the application. It can then be moved to a proper machine/directory for extraction and execution. The generated package is `target/universal/akka-dodex-scala-1.0.zip`.
    * Building a fat jar using the `assembly` plugin requires a hack for an `Akka` application. Basically the plugin cannot determine the full `Akka` configuration.

        1. Using a Java based package @ <https://github.com/DaveO-Home/jin> to generate the `Fat Jar`
      
        2. Jin must be installed in the `src` directory. The simplist method is to `cd src` and execute `git clone https://github.com/DaveO-Home/jin.git`.

        3. In the project directory where `GenFatJar` is located, execute `export WD=.`, `export CD=${PWD}/target/classes` and `mkdir target/classes`.
      
        4. Execute `./GenFatJar` to build the Fat Jar.

        5. The generated jar should be `target/scala-2.13/akka-dodex-scala-assembly-1.0.jar`.

        6. Execute `java -jar target/scala-2.13/akka-dodex-scala-assembly-1.0.jar` to startup production.

## Cassandra

  * If `cqlsh` is installed, to access the local embedded database while `dodex-akka` is runnig, this might be useful - `cqlsh 127.0.0.1 --cqlversion=3.4.4 --encoding=utf8`.
  * For a `Cassandra 4` setup this might be useful - `cqlsh <production host> 9042 --cqlversion="3.4.5"`.

## Test Dodex

1. Make sure the demo Java-vertx server is running in development mode.
2. Test Dodex-mess by entering the URL `localhost:3087/test/index.html` in a browser.
3. Ctrl+Double-Click a dial or bottom card to popup the messaging client.
4. To test the messaging, open up the URL in a different browser and make a connection by Ctrl+Double-Clicking the bottom card. Make sure you create a handle.
5. Enter a message and click send to test.
6. For dodex-input Double-Click a dial or bottom card to popup the input dialog. Allows for uploading, editing and removal of private content. Content in JSON can be defined as arrays to make HTML more readable.


### Optimizing with Graalvm

* See <https://www.scala-sbt.org/sbt-native-packager/formats/graalvm-native-image.html> to implement.

__Note;__ This worked with GraalVM Version 20.1.0 (Java Version 11.0.7)

### Single Page React Application to demo Development and Integration Testing

* Not yet implemented for `Cassandra`

### Additional References

* Scala, Sbt, Akka and Cassandra documentation.

  1. Scala; <https://docs.scala-lang.org/overviews/index.html>
  2. sbt; <https://www.scala-sbt.org/1.x/docs/index.html>
  3. Akka; <https://doc.akka.io/docs/akka/current>
  4. Cassandra; <https://cassandra.apache.org/doc/latest/>
  5. sbt native plugin; <https://www.scala-sbt.org/sbt-native-packager/gettingstarted.html>

## ChangeLog

<https://github.com/DaveO-Home/dodex-akka/blob/master/CHANGELOG.md>

## Authors

* *Initial work* - [DaveO-Home](https://github.com/DaveO-Home)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details
