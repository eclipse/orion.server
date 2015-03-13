The goal of Orion is to build developer tooling that works in the browser, at web scale.
The vision behind Orion is to move software development to the web as a web experience, by
enabling open tool integration through HTTP and REST, JSON, OAuth, OpenID, and others.
The idea is to exploit internet design principles throughout, instead of trying to bring
existing desktop IDE concepts to the browser. See the [Orion wiki](http://wiki.eclipse.org/Orion) for more
information about Orion.

Contributing
------------

Orion source code is available in an Eclipse Git repository, and there is also a mirror
on GitHub. For complete details on getting the source and getting setup to develop Orion,
see the [Orion wiki](http://wiki.eclipse.org/Orion/Getting_the_source).

Bug reports and patches are welcome in [bugzilla](https://bugs.eclipse.org/bugs/enter_bug.cgi?product=Orion).

License
-------

This repository contains the Orion Java server, which is available under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html).

How to build Orion using Maven
------------------------------

Install Maven:
- install latest Maven 3.0 from http://maven.apache.org/download.cgi
- follow http://maven.apache.org/settings.html to configure Maven settings.xml

Clone Git repositories:
- clone `org.eclipse.orion.client` and `org.eclipse.orion.server` under the same local folder
-  `cd /my/git/repos`
-  `git clone http://git.eclipse.org/gitroot/orion/org.eclipse.orion.client.git`
-  `git clone http://git.eclipse.org/gitroot/orion/org.eclipse.orion.server.git`
  
Run Maven build
- `cd org.eclipse.orion.server/`
- `mvn clean install`


Eclipse Setup
-------------

Set target platform:
- in Eclipse open the target definition `org.eclipse.orion.server/releng/org.eclipse.orion.target/org.eclipse.orion.target`
- click "Set as Target Platform"

