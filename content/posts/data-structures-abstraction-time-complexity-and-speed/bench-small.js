var Benchmark = require('benchmark');

var suite = new Benchmark.Suite;

var set = new Set();
set.add(1);
set.add(2);
set.add(3);
var array = [];
array.push(1,2,3);

// add tests
suite.add('Set.has (small)', function() {
  set.has(1)
  set.has(2)
  set.has(3)
  set.has(0)
})
.add('Array.includes (small)', function() {
  array.includes(1)
  array.includes(2)
  array.includes(3)
  array.includes(0)
})
// add listeners
.on('cycle', function(event) {
  console.log(String(event.target));
})
.on('complete', function() {
  console.log('Fastest is ' + this.filter('fastest').map('name'));
})
// run async
.run({ 'async': true });
