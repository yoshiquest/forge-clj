# forge-clj: Clojure in Minecraft

## Description

A WIP implementation of Clojure working with Minecraft Forge. This is now somewhat useable!

I now have a tutorial (CURRENTLY OUTDATED)! It's still a work in progress, but it's [here](https://github.com/yoshiquest/forge-clj/wiki "Tutorials"). A [test-mod](https://github.com/yoshiquest/test-mod "test-mod") also exists, which I use for making sure everything works, so there should be an example for everything currently implemented in there.

## Usage (for users)

Simply download the latest version of forge-clj, as well as the mod you want to run, and put them both in the mods folder.

## Usage (for modders)

First off, extract Forge into a folder. Then download the latest version of the forge-clj dev kit, and extract its contents into the dev environment. Afterwords, be sure to rename the things in the build.gradle to use your package names and not mine, and then run the commands to set up forge as normal.

If this doesn't work be sure to tell me so I can fix it.

When getting ready to deploy your mod, be sure to use gradle build (not runclient!) first. For some reason the jar generated by runclient is broken, and the one generated by build isn't.

## License

Copyright © 2015-2016 Ryan Haney (Yoshiquest)

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
