# CraftionSpawner Baseline Smoke Test

This is a manual checklist to verify the SmartSpawner upstream baseline behaves correctly on Paper/Folia.

## Environment
- **Server Implementation:** 
- **Server Version:** 
- **Java Version:** 
- **Plugin JAR Hash:** 
- **Database Type:** 
- **Operating System:** 

## Plugin Startup
- [ ] plugin enables without stack traces
- [ ] database initializes
- [ ] migrations complete
- [ ] shutdown completes cleanly

## Basic Spawner Flow
- [ ] admin can obtain a supported spawner
- [ ] spawner can be placed
- [ ] GUI can be opened
- [ ] spawner can be stacked
- [ ] virtual production generates items
- [ ] XP generation behaves as expected
- [ ] storage can be opened
- [ ] generated items can be withdrawn
- [ ] XP can be claimed
- [ ] spawner can be broken
- [ ] stack count is preserved correctly

## Persistence
- [ ] restart server
- [ ] spawner still exists
- [ ] stack amount remains correct
- [ ] stored items remain correct
- [ ] stored XP remains correct
- [ ] no duplicate records appear

## Folia/Luminol
- [ ] no async entity access warnings
- [ ] no region-thread violations
- [ ] chunk load/unload does not throw errors
- [ ] spawner continues producing after reload/restart

## Result
- **PASS:** [ ]
- **FAIL:** [ ]
- **NOT RUN:** [ ]
- **Tester:** 
- **Date:** 
- **Notes:** 
