# doDex-akka, a scala asynchronous/reactive client to doDex-vertx supporting the Cassandra nosql database for Dodex, Dodex-input and Dodex-mess

## Install Assumptions

1. Java 17 or higher installed with JAVA_HOME set.
2. Scala 3.4.2 and `sbt` installed.
3. Node/npm javascript package manager installed.
4. Docker(to install a test Cassandra container) or installed Cassandra
5. Optionally `cqlsh`

## Motivation and Summary

A maiden voyage into the Scala, Akka and Cassandra world. The Akka client attaches to the Vertx Event Bus Bridge using TCP. Daniel Stieger's project @ <https://github.com/danielstieger/javaxbus> is used to communicate with the event bus on the Vertx Server. On the Akka side this code was converted to Scala and used to fomulate the message. The development/testing environment uses either a __Cassandra container__ or an installed __networked Cassandra__. View and change the configuration in ``application.conf``. The asynchronous/reactive code is much like Vertx using Futures and Promises. Conclusion, Akka is a good place to start if you want to learn Scala and the Actor/Behavior pattern. The need for a Dodex database is simple so Cassandra usage barely scratches the surface.

__Note;__ dodex-Akka can be run/developed on most platforms since __cqlsh__ is now supported with python 3.

## Getting Started

1. Download methods:
   * `npm install dodex-akka`
   * `gh repo clone DaveO-Home/dodex-akka`
   * `git clone` from <https://github.com/DaveO-Home/dodex-akka>  
   If you use npm install, move node_modules/dodex-akka to an appropriate directory.
2. Install Cassandra or use the provided script __`cassDocker.sh`__ to create a Docker container. When using the Docker container,  additional Cassandra configuration is not necessary to get started. The Cassandra container runs @ 127.0.0.1:9042.
3. Edit `src/main/resources/application.conf` and change the following;
    * modify `cassandra-service` host values to reflect a networked Cassandra
    * modify ``event.bus.dev.host`` to reflect the location of the development Vertx micro-service
    * modify `event.bus.host` to reflect the location of the production Vertx micro-service
    * also change the `port` values if test and production are running on the same machine simultaneously. Make sure the `bridge.port` value in `application-conf.json` for the Vertx micro-service corresponds to these values.

4. `cd <install directory>/dodex-akka` and execute `sbt` to enter the sbt shell and execte `run`. This should install Scala dependencies and startup the micro-service in development mode against the installed Cassandra database. Review instructions below on Akka development.

5. On the Vertx side, make sure dodex-vertx is running with `Cassandra` database set.
    * Method 1; `export DEFAULT_DB=cassandra` or `Set DEFAULT_DB=cassandra` before starting the `Dodex-Vertx` micro-service.
    * Method 2; change `defaultdb` to `cassandra` in `database_config.json` file before starting vertx. 

6. With both Vertx and Akka sevices running, execute url `http://localhost:8087/test` in a browser. To test that the `Akka` service is working, follow instructions for `Dodex-Mess`. If the message box displays `connected` you are good to go.  
   __Note:__ The Vertx service is started with `Cassandra` if the startup message `TCP Event Bus Bridge Started` is displayed.
7. You can also run `http://localhost:8087/test/bootstrap.html` for a bootstrap example.
8. Follow instructions for dodex at [dodex-mess](https://www.npmjs.com/package/dodex-mess>) and [dodex-input](https://www.npmjs.com/package/dodex-input).

### Using the Vert.x Mqtt Broker and Akka Mqtt Client

1. The order of precedence for starting Sbt in development mode to run the __Mqtt Client__.
   *  Execute `sbt`,  `set run / fork := true`, `~run mqtt=true`
   *  Execute `export USE_MQTT=true`, `sbt`, `set run / fork := true`, `~run`
   *  In file __src/main/resources/application.conf__ set __use.dev.mqtt=true__, execute `sbt`, `set run / fork := true`, `~run`
      *  For production use __use.mqtt=true__
      *  The __TCP/Event Bus__ connection is default.
      
__Note:__ To start the __Vert.x Mqtt Broker__, see [dodex-vertx README](https://github.com/DaveO-Home/dodex-vertx/blob/master/README.md)

### Operation

1. Starting in Dev Mode to test implementation; execute `sbt` and then  `run`. The `dodex-vertx` service should be running first, however if `dodex-akka` is started first, the Akka service will continue attempting the TCP handshake for a limited number of tries. This can be configured in `Limits.scala`. Conversely, if `dodex-vertx` is shutdown, the Akka client will continue with attempts to reconnect a limited number of times, frequency can also be configured.

2. Starting in Dev Mode to develop; execute `sbt` to start the `sbt` shell  
   __Note:__ These commands should work when changing `Akka` code.
    * execute `set run / fork := true`, this allows the application to reload when shutting down with `ctrl-c`
    * execute `set run / javaOptions += "-Ddev=true"` for forked JVM and then execute `~run` to start
    * modify some `Akka` code and execute `ctrl-c`, while still in the `sbt shell` and using `~run` the `Akka` service will restart.
    * When changing and restarting the `Vert.x` service, the `Akka` service should auto restart.

3. Building a Production distribution - Review the `sbt` plugin documentation for details
    * Try `sbt stage` this will build a production setup without packaging.  You can execute by running `target/universal/stage/bin/akka-dodex-scala`. Make sure your production database is running.
    * Execute `sbt "Universal / packageBin"` to package the application. It can then be moved to a proper machine/directory for extraction and execution. The generated package is `target/universal/akka-dodex-scala-2.0.zip`.
    * Building a fat jar using the `assembly` plugin requires a hack for an `Akka` application. Basically the plugin cannot determine the full `Akka` configuration.

      1. Using a Java based package @ <https://github.com/DaveO-Home/jin> to generate the `Fat Jar`
      2. Jin must be installed in the `src` directory. The simplist method is to `cd src` and execute `git clone https://github.com/DaveO-Home/jin.git`.
      3. In the project directory where `GenFatJar` is located, execute `export WD=.`, `export CD=${PWD}/target/classes` and `mkdir target/classes`.
      4. Execute `./GenFatJar` to build the Fat Jar.
      5. The generated jar should be `target/scala-3.4.2/akka-dodex-scala-assembly-2.0.jar`.
      6. Execute `scala target/scala-3.4.2/akka-dodex-scala-assembly-2.0.jar` to startup production.
      7. Or using Java you can execute `./runUber`.

## Cassandra

  * If `cqlsh` is installed, to access the local database, this might be useful - `cqlsh 127.0.0.1 --cqlversion=3.4.6 --encoding=utf8`.
  * For a `Cassandra 4` setup this might be useful - `cqlsh <production host> 9042 --cqlversion="3.4.6"`.
  * Other wise just execute `cqlsh`, `use dodex;` and `describe tables;`.

## Test Dodex

1. Make sure the demo Java-vertx server is running in development mode.
2. Test Dodex-mess by entering the URL `localhost:3087/test/index.html` in a browser.
3. Ctrl+Double-Click a dial or bottom card to popup the messaging client.
4. To test the messaging, open up the URL in a different browser and make a connection by Ctrl+Double-Clicking the bottom card. Make sure you create a handle.
5. Enter a message and click send to test.
6. For dodex-input Double-Click a dial or bottom card to popup the input dialog. Allows for uploading, editing and removal of private content. Content in JSON can be defined as arrays to make HTML more readable.


### Optimizing with Graalvm

* See <https://www.scala-sbt.org/sbt-native-packager/formats/graalvm-native-image.html> to implement.

### Single Page React Application to demo Development and Integration Testing

* For details see <https://github.com/DaveO-Home/dodex-vertx/blob/master/src/spa-react/README.md>
* In the vertx directory .../src/spa-react/devl, the following commands can be executed, assuming the the app was installed in "spa-react" by running `npm install`.
* After setting the database to `cassandra`, co-ordination among `Akka`, `Vertx` and `React` builds is difficult to automate. Therefore using `npx gulp test` and `npx gulp prod` will not work. For a test build use `npx gulp rebuild` and once `Vertx` and `Akka` are restarted, tests can be executed with `npx gulp acceptance`. For the `React` production build, use `npx gulp prd`.

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
