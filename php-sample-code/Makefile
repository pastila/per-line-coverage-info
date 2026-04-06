.PHONY: test behat behat-calc behat-coffee install seed-generate

test: behat

behat: behat-calc behat-coffee

behat-calc:
	./dude run bash -c './calc/vendor/bin/behat -c ./calc/behat.yml'

behat-coffee:
	./dude run bash -c './coffee/vendor/bin/behat -c ./coffee/behat.yml'

install:
	./dude run bash -c 'cd calc && composer install && cd ../coffee && composer install'

seed-generate:
	bash ../api/seed/generate.sh