# Official reporter 2.0.0 fixture

`calculator.covt` was generated with the unmodified source archive of
`raketa-libraries/behat-coverage-per-line-report` tag `2.0.0`, commit
`7bab217a542fb8a44f9e5fe662bb7cb8474713e0`, using PHP 8.5 and
`phpunit/php-code-coverage` 14.5.1.

The reporter's own `tests/fixtures` sample was placed in `<repo>/app/raketa`,
with its Behat config in `features/behat.yml` (as in the application).
Both calculator scenarios were run with PCOV enabled and these options:

```text
--binary-coverage-target=<repo>/storage/coverage/local.covt
--binary-coverage-repo-root=<repo>
```

The report contains the original scenario paths and lines (`3`, `8`),
covered `Calculator.php` lines (`11`, `16`), and uncovered line `21`.
It exercises the same parser and path normalization as local report imports.
