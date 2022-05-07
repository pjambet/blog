var Benchmark = require('benchmark');


function getRandomInt(max) {
	return Math.floor(Math.random() * max);
}

var set = new Set();
var array = [];

function runWithSize(size, random) {
	var suite = new Benchmark.Suite;
	for (i=1;i<=size;i++) {
		set.add(i);
		if (random) {
			let index = getRandomInt(array.length);
			array.splice(index, 0, i);
		} else {
			array.push(i);
		}
	}

	suite
		.add('Set.has ('+size+')', function() {
			set.has(1)
			set.has(2)
			set.has(3)
			set.has(9999)
			set.has(0)
		})
		.add('Array.includes ('+size+')', function() {
			array.includes(1)
			array.includes(2)
			array.includes(3)
			array.includes(9999)
			array.includes(0)
		})
		.on('cycle', function(event) {
			console.log(String(event.target));
		})
		.on('complete', function() {
			console.log('Fastest is ' + this.filter('fastest').map('name'));
		})
		.run({ 'async': false });
}

// runWithSize(3, false);
// runWithSize(10, false);
// runWithSize(10000, false);
// runWithSize(20000, false);
// runWithSize(30000, false);
runWithSize(40000, false);
runWithSize(50000, false);
// runWithSize(60000, false);
// runWithSize(70000, false);
// runWithSize(80000, false);
// runWithSize(90000, false);
// runWithSize(100000, false);
