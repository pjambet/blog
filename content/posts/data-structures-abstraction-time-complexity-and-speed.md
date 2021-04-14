---
title: "Data Structures, Abstractions, Time Complexity and Speed"
date: 2021-04-13T12:56:29
lastmod: 2021-04-13T12:56:42
tags : [ "dev", "ruby", "js", "scala", "redis" ]
categories : [ "dev", "ruby", "js", "scala", "redis" ]
layout: post
highlight: false
draft: false
summary: "A better time complexity does not mean faster. In this article we look at various abstractions such as Sets and Maps in different languages (and in Redis)."
---

_This article assumes some degree of understanding of data structures such as arrays, lists, maps, also called
dictionaries or associative arrays. If you’re not already familiar with these, the [Wikipedia
page](https://en.wikipedia.org/wiki/Data_structure) on data structures might be a good start._

**tl;dr;** A data structure with a worst time complexity might be faster for a given operation than another one with a better time complexity, under the right circumstances. It’s often the case with small collections where a list or an array might outperform a hash table for the “contains” operation despite the time complexities being respectively O(n) and O(1). Many abstractions provided by programming languages already do some form of optimizations, such as the `Set` class in scala. Redis does something similar for hashes and sorted sets.

## Does this collection contain this item?

Let’s assume we have some integers in memory, and we want to check if a value entered by the user is contained within these integers. A potential solution in JS would look like this:

``` js
let numbers = [1, 2, 3, 4, 5]
numbers.includes(2) // => true
numbers.includes(6) // => false
```

The `Array.includes` function does exactly what we want, great. You’re happy with your solution, you commit this, open a PR and receive the following comment:

> We should use a Set instead of an Array here, and use the `has` method instead of `includes`, since it has an O(1) complexity (includes is O(n)).

The second half of the comment about time complexity is true, the first part though, debatable. Should we use the one with the better time complexity? If so, why?

Let’s look as some numbers with the benchmark npm package:

``` js
var Benchmark = require('benchmark');

var suite = new Benchmark.Suite;

var set = new Set();
set.add(1);
set.add(2);
set.add(3);
var array = [];
array.push(1,2,3);

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
.on('cycle', function(event) {
  console.log(String(event.target));
})
.on('complete', function() {
  console.log('Fastest is ' + this.filter('fastest').map('name'));
})
.run({ 'async': true });
```

The following is the result I got on my machine (a 2020 mac mini):

```
Set.has (small) x 25,580,981 ops/sec ±0.67% (93 runs sampled)
Array.includes (small) x 1,014,367,099 ops/sec ±0.28% (99 runs sampled)
Fastest is Array.includes (small)
```

The array version is faster, by a lot, we're looking at a ~40x difference here. Another takeaway, both versions are fast enough for many use cases. Even the set version, the slowest version here, was able to run ~25 million operations per second. On average each call to `has` took ~0.00000004s, that's 0.00004ms or 0.04μs or 40ns. For `includes` it is 0.000000001s, 0.000001ms, 0.001μs and 1ns.

I ran the same benchmark on the cheapest Digital Ocean droplet on ubuntu and got similar relative results, everything being slower, ~10 million ops/s for `Set.has` and ~310 million ops/s for `Array.includes`.

The conclusion here is that for a teeny tiny collection, using nodejs 15, an Array is way faster than a Set. Things might be different in a browser, I don't know how the different engines have implemented Arrays and Sets.

So why do we care about time complexity if it's not an indicator of speed? It's because it tells us how different the execution time will be depending on how many "things" we're dealing with. That's the n piece, in this case, the number of items in the Array or Set.

Let's run the same benchmark, but with a larger collection this time. I re-wrote it so we can parameterize the size of the collection. The items we search in the collections are always the same to try to make it an "apple to apple" comparison. The code is available [in this Gist][gist-bench-large].

Up until 40,000 items, the Array lookup is faster, ~1 billion ops/s vs ~20 million ops/s. With 50,000 items, the Set version still runs about the same number of operations per second, ~20 millions, but the Array one drops to ~500,000 ops/s.

That's why we care about time complexity, not because it tells us if one version is faster than another but because it tells us how the running time changes depending on the input size. O(1), aka "constant time complexity", tells us that the Set version will be roughly the same regardless of how many items we throw at it. On the other hand, O(n), aka "linear time complexity", will slowly gets slower and slower as the input size grows. In the array case, this makes sense, to return a response for `array.includes(0)`, we need to iterate through the entire array to return `false`, the number of iterations is directly correlated to the size of the array.

---

So with this newfound knowledge, should we change the implementation? If it was up to a vote, I'd vote for using a `Set`, but not because it has a better complexity. Let's be honest, in many many cases, the time difference observed here doesn't make a difference, but because it conveys the intent a little bit more explicitly. By using a `Set` you're saying that you're dealing with a unique collection of integers, whereas an Array is not as specific.

### Is this specific to JavaScript?

No.

I ran a similar test in ruby, comparing an Array, a Hash and a Set, and obtained the following results with respectively three items in the collections, and 1,000,000:

```
Warming up --------------------------------------
             Set (3)   592.205k i/100ms
           Array (3)   782.067k i/100ms
            Hash (3)   863.547k i/100ms
Calculating -------------------------------------
             Set (3)      5.915M (± 1.1%) i/s -     29.610M in   5.006554s
           Array (3)      7.771M (± 1.4%) i/s -     39.103M in   5.032784s
            Hash (3)      8.627M (± 0.7%) i/s -     43.177M in   5.004990s

Comparison:
            Hash (3):  8627331.0 i/s
           Array (3):  7771408.3 i/s - 1.11x  (± 0.00) slower
             Set (3):  5915026.1 i/s - 1.46x  (± 0.00) slower

Warming up --------------------------------------
       Set (1000000)   578.593k i/100ms
     Array (1000000)     3.159k i/100ms
      Hash (1000000)   794.715k i/100ms
Calculating -------------------------------------
       Set (1000000)      5.702M (± 1.0%) i/s -     28.930M in   5.074141s
     Array (1000000)     31.556k (± 1.4%) i/s -    157.950k in   5.006397s
      Hash (1000000)      7.955M (± 1.7%) i/s -     40.530M in   5.096204s

Comparison:
      Hash (1000000):  7955389.3 i/s
       Set (1000000):  5701948.8 i/s - 1.40x  (± 0.00) slower
     Array (1000000):    31555.9 i/s - 252.10x  (± 0.00) slower
```

With only three elements, the Set is the slowest option, and surprisingly the Hash is the fastest. I did not dig in the C source of Ruby to figure out why, but I have to assume that there a lot of internal optimizations that skip over some of the unnecessary steps. This is likely similar to what Scala does, which we will take a look at in the next section.

With a larger collection, 1,000,000 items, the Array becomes the slowest option, as we expected, since it has an O(n) time complexity, aka, the larger the array, the slower it is to iterate through it. The Hash and Set have similar numbers, due to their constant time complexity.

The code is available in [this Gist][gist-ruby]

## Sets and Maps in scala

I did not run any benchmarks in Scala, but I thought it was really interesting to look at the source of `Set` and `Map` in the standard library.

Scala's standard library provides `Set`. What makes it interesting is that `Set` is a trait (if you're not familiar with Scala, it's _very_ similar to a Java interface), and the actual class that you get depends on the size of the set:

```scala
scala> Set(1).getClass
val res0: ... scala.collection.immutable.Set$Set1

scala> Set(1, 2).getClass
val res1: ... scala.collection.immutable.Set$Set2

scala> Set(1, 2, 3).getClass
val res2: ... scala.collection.immutable.Set$Set3

scala> Set(1, 2, 3, 4).getClass
val res3: ... scala.collection.immutable.Set$Set4

scala> Set(1, 2, 3, 4, 5).getClass
val res4: ... scala.collection.immutable.HashSet

scala> Set(1, 2, 3, 4, 5, 6).getClass
val res5: ... scala.collection.immutable.HashSet
```

Scala has four different concrete classes, all implementing the `Set` trait, `Set1`, `Set2`, `Set3`, `Set4` and `HashSet`. The first four are very similar, they are written as classes with, respectively, one, two, three and four members. This makes the implementation of the `contains` method (similar to `has` in JS) pretty concise:

```scala
def contains(elem: A): Boolean = elem == elem1
```

`elem1` is the single member of the `Set1` class.

and for `Set2`, `Set3` and `Set4`:

```scala
// Set2:
def contains(elem: A): Boolean = elem == elem1 || elem == elem2
// Set3:
def contains(elem: A): Boolean =
  elem == elem1 || elem == elem2 || elem == elem3
// Set4:
def contains(elem: A): Boolean =
  elem == elem1 || elem == elem2 || elem == elem3 || elem == elem4
```

You can see the source of all four in the [Set.scala file][scala-source-set].

The final class, `HashSet`, is the "real" one, where the size of the `Set` could be anything. It uses a hashing algorithm to implement the O(1) `contains` method. The source is available in the [HashSet.scala file][scala-source-hash-set]

Scala does the same thing with `Map`, where `Map1` has two members, a key and a value, `Map2` has four members, two keys and two values, and so on. The source is available in the [Map.scala file][scala-source-map]. For `Map` instances containing more than four pairs, it uses a [`HashMap`][scala-source-hash-map]

## Hashes, Sets and Sorted Sets in Redis

Redis provides Hashes, Sorted Sets and Sets, among other data types. For each of these, it uses two different underlying data structures, one for small collections, and one for larger collections.

For Hashes, if the number of items is below the `hash-max-ziplist-entries` config value, which has [a default value of 512][redis-conf-hash]), Redis uses a ziplist, which is essentially an optimized linked list. No hashing algorithm, no collision, none of that. You send the `HEXISTS` command to Redis for a hash with less 512 keys, it will iterate through the list of key and values until it finds it, or not. You can see it for yourself [in the source][redis-source-hexists].

For Sorted Sets, it uses a similar approach, it also stores entries in a ziplist. This is controlled by the `zset-max-ziplist-entries` config, which has [a default value of 128][redis-conf-zset].

Redis Sets use a Hash under the hood, but only when the number of entries is greater than `set-max-intset-entries`, which has [a default value of 512][redis-conf-set]. An `intset` is essentially a sorted array of integers.

## Conclusion

If I have one piece of advice to wrap this post with, please do not use this as a justification to replace a `Set` in JavaScript or Ruby with something else, and justifying with “but it is faster!”. Unless it matters for your application that the has/contains operation takes 1ns instead of 40ns, but I think it's safe to say that in many cases, it probably doesn't matter.

I personally try to avoid as much as possible the terms “fast” and “slow”. They tend to be meaningless without the appropriate context. Instead I like to ask the question “is it fast enough?”, or “this is not the fastest implementation, but does it matter?”.

And if you want to learn more about Redis and how it _actually_ works, I'm writing a book about it: [Rebuilding Redis in Ruby](https://redis.pjam.me).

## The pedantic corner

I took some liberties with the terms "Data Structures" and "Abstractions" in this article. They're not the same and it _can_ be important to differentiate them. Following the [Wikipedia page for Abstract Data Types (ADTs)](https://en.wikipedia.org/wiki/Abstract_data_type):

> An abstract data type is defined by its behavior (semantics) from the point of view of a user, of the data, specifically in terms of possible values, possible operations on data of this type, and the behavior of these operations. This mathematical model contrasts with data structures, which are concrete representations of data, and are the point of view of an implementer, not a user

With this definition, if we were to label the elements we looked at in the Scala example, the `Set` and `Map` traits are ADTs, and `HashSet` and `HashMap` are the [data structures](https://en.wikipedia.org/wiki/Data_structure) providing the actual implementations. `Set1`, `Map1` and the other similar ones would be categorized as [Record data structures](https://en.wikipedia.org/wiki/Record_(computer_science)).


[gist-bench-large]:https://gist.github.com/pjambet/e3a5e40999de967b9b37ae75daac6d3f
[gist-ruby]:https://gist.github.com/pjambet/98cb38157147619f0d004b622acd02ae
[scala-source-set]:https://github.com/scala/scala/blob/8a2cf63ee5bad8c8c054f76464de0e10226516a0/src/library/scala/collection/immutable/Set.scala#L156
[scala-source-hash-set]:https://github.com/scala/scala/blob/8a2cf63ee5bad8c8c054f76464de0e10226516a0/src/library/scala/collection/immutable/HashSet.scala
[scala-source-map]:https://github.com/scala/scala/blob/2.13.x/src/library/scala/collection/immutable/Map.scala#L241
[scala-source-hash-map]:https://github.com/scala/scala/blob/2.13.x/src/library/scala/collection/immutable/HashMap.scala
[redis-conf-hash]:https://github.com/redis/redis/blob/6.2.1/redis.conf#L1676
[redis-conf-zset]:https://github.com/redis/redis/blob/6.2.1/redis.conf#L1720
[redis-conf-set]:https://github.com/redis/redis/blob/6.2.1/redis.conf#L1715
[redis-source-hexists]:https://github.com/redis/redis/blob/6.2.1/src/t_hash.c#L55-L85
