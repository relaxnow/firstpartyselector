Firstpartyselector
==================

***DO NOT USE THIS, USE scan_all_top_level_modules INSTEAD, THIS ALREADY ENSURES NO 3rd PARTY MODULES ARE SELECTED***

Example project on using the API to automatically produce a scan where all first party modules are selected.
Uses a published copy of verademo-dotnetcore from https://github.com/veracode/verademo-dotnetcore/tree/master/app .

Note that the ['beginscan.do'](https://docs.veracode.com/r/r_beginscan) API supports 'scan_all_top_level_modules',
this may however include third party modules. Veracode Static Analysis does not currently expose which modules
are third party.

This example then does 2 scans:
1. An "SCA SCAN" to get the 'detailedreport' with SCA component file_names.
2. The actual "STATIC SCAN" where module names that match file names from the "SCA SCAN" components are ignored.

Note that the ``appId`` is hardcoded in the ```uploadCommand```.

Running it can be done with ```mvn -DskipTests clean package && java -jar target/firstpartyselector-0.0.1-SNAPSHOT.jar```.
