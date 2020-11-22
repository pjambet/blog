---
title: "Ruby Symbol to Proc explained, the short version"
date: 2020-11-21T17:05:48-05:00
lastmod: 2020-11-21T17:05:48-05:00
tags : [ "dev", "ruby" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: false
summary: What does "ampersand, symbol" as an argument to a ruby method actually do? It creates a proc.
---

## What's with this weird looking syntax with an ampersand and a symbol

When `&:object_id` is used as an argument to a method call, it will convert the symbol `:object_id` into a `Proc` instance with the [`Symbol#to_proc`][ruby-doc-symbol-to-proc] method.

This syntax is really useful when mixed with [`Enumerable`][ruby-doc-enumerable] methods such as [`map`][ruby-doc-map] and [`select`][ruby-doc-map]:

``` ruby
irb(main):001:0> [ 1, 2, 3, 4, 5 ].select(&:even?)
=> [2, 4]
irb(main):002:0> [ 1, 2, 3, 4, 5 ].map(&:even?)
=> [false, true, false, true, false]
```

The result of `:even?.to_proc` is a `Proc` instance, very similar to `proc { |x| x.even? }` with one _major_ difference. They handle arguments differently. A proc is not as strict as a method, you give it too many arguments, it ignores them, you don't give enough, it fills the missing values with `nil`:

``` ruby
irb(main):001:0> a_proc = proc { |a, b, c| p a, b, c }
irb(main):002:0> a_proc.call(1)
1
nil
nil
=> [1, nil, nil]
irb(main):003:0> a_proc.call(1,2,3,4)
1
2
3
=> [1, 2, 3]
```

We can inspect the arity, to see the number of parameters:

``` ruby
irb(main):004:0> a_proc.arity
=> 3
```

And the [`Proc#parameters`][ruby-doc-parameters] method confirms that all three are optional:

``` ruby
irb(main):005:0> a_proc.parameters
=> [[:opt, :a], [:opt, :b], [:opt, :c]]
```

Note that Lambdas, beside handling `return` and `break` differently, treat their arguments as required:

``` ruby
irb(main):006:0> a_lambda = lambda { |a, b, c| puts a, b, c }
irb(main):007:0> a_lambda.arity
=> 3
irb(main):008:0> a_lambda.parameters
=> [[:req, :a], [:req, :b], [:req, :c]]
```

On the other hand, the result of [`Symbol#to_proc`][ruby-doc-symbol-to-proc] is an interesting hybrid. We can confirm that it is _not_ a lambda:

``` ruby
irb(main):017:0> :even?.to_proc.lambda?
=> false
```

But it handles arguments differently than a _regular_ block, let's first check its arity:


``` ruby
irb(main):020:0> :even?.to_proc.arity
=> -1
irb(main):022:0> :even?.to_proc.parameters
=> [[:rest]]
```

`-1` is what you'd expect from the following `Proc`, one that accepts any number of arguments, but we can see that the result of `parameters` is different. `Symbol#to_proc` creates a block with a nameless parameter.

``` ruby
irb(main):001:0> proc { |*a| p a }.arity
=> -1
irb(main):002:0> proc { |*a| p a }.parameters
=> [[:rest, :a]]
```

We can get close to the `to_proc` result with the following, but there's not much we can do with a nameless parameter, sometimes called "naked parameter". This _can_ be useful with methods, as calling `super` will forward all the arguments to the parent method, but there's no parent method with a block!

``` ruby
irb(main):001:0> proc { |*| }.parameters
=> [[:rest]]
```

Back to the result of `Symbol#to_proc`, we get exceptions back with no arguments, or more than one:

``` ruby
irb(main):023:0> :even?.to_proc.call
Traceback (most recent call last):
        4: from /Users/pierre/.rbenv/versions/2.7.1/bin/irb:23:in `<main>'
        3: from /Users/pierre/.rbenv/versions/2.7.1/bin/irb:23:in `load'
        2: from /Users/pierre/.rbenv/versions/2.7.1/lib/ruby/gems/2.7.0/gems/irb-1.2.3/exe/irb:11:in `<top (required)>'
        1: from (irb):23
ArgumentError (no receiver given)
irb(main):024:0> :even?.to_proc.call(1, 2)
Traceback (most recent call last):
        6: from /Users/pierre/.rbenv/versions/2.7.1/bin/irb:23:in `<main>'
        5: from /Users/pierre/.rbenv/versions/2.7.1/bin/irb:23:in `load'
        4: from /Users/pierre/.rbenv/versions/2.7.1/lib/ruby/gems/2.7.0/gems/irb-1.2.3/exe/irb:11:in `<top (required)>'
        3: from (irb):23
        2: from (irb):24:in `rescue in irb_binding'
        1: from (irb):24:in `even?'
ArgumentError (wrong number of arguments (given 1, expected 0))
```

One argument is required, so that the method identified by the symbol's value can be called on it, `Kernel#object_id` and `Integer#even?`in the examples above, and remaining arguments are forwarded to the method. In this case `even?` does not accept any and we get an `ArgumentError` exception with the cause `wrong number of arguments (given 1, expected 0)`.

### It's not just for parameter-less methods

**A common mistake I've seen is to think that this approach limits us to parameter-less methods**, such as [`Integer#even?`][ruby-doc-even?], the following is an example using the ["spaceship operator" method][ruby-doc-spaceship-operator], which is defined as :

> int <=> numeric → -1, 0, +1, or nil

And the [sort][ruby-doc-sort] method has the following method signature according to the Ruby docs:

> sort { |a, b| block } → array

``` ruby
irb(main):004:0> [ 5, 3, 1, 2, 4 ].sort(&:<=>)
=> [1, 2, 3, 4, 5]
```

The method `<=>` was called with two arguments, the two array elements it needs to compare, it is very close to the following more explicit approach:

``` ruby
irb(main):004:0> [ 5, 3, 1, 2, 4 ].sort { |a, b| a.<=>(b) }
=> [1, 2, 3, 4, 5]
```

### We can pass almost anything after the ampersand

The call to `to_proc` is triggered in the first place because when handling a method call, Ruby needs to make sure that if it received a block argument, that this argument is actually a proc.

The ampersand character has itself nothing to do with the symbol, or whatever comes after it. The ampersand's role in an argument list is to identify the block argument, which must come last if present.

Note that while it is very common to see `&` used with symbols, and procs themselves when passed as block arguments to a method, in true duck-typing fashion, _anything_ that responds to `to_proc` will work. For instance `Hash#to_proc` returns a proc that accepts one argument and returns either `nil` or the `value` at the key identified by the one argument:

``` ruby
irb(main):001:1* def a_method(&block)
irb(main):002:1*   block.call(1)
irb(main):003:0> end
=> :a_method
irb(main):004:0> a_method(&{ 1 => 'one' })
=> "one"
irb(main):005:0> a_method(&{ 2 => 'two' })
=> nil
```

And we could pass our own class, as long as it responds to `to_proc`:

``` ruby
irb(main):004:1* class A
irb(main):005:2*   def self.to_proc
irb(main):006:2*     proc { puts "Not really useful but it works" }
irb(main):007:1*   end
irb(main):008:0> end
=> :to_proc
irb(main):009:0> a_method(&A)
Not really useful but it works
=> nil
```

There are four classes defined with a `to_proc` method in the standard library: `Symbol`, `Method`, `Proc` and `Hash`. `Enumerator::Yielder` is not counted as a "standard library" class given its nature as an implementation detail of `Enumerator`.

#### Links:

Thank you to [Étienne Barrié][twitter-etienne-barre] for pointing me to the following links. Rails used to have its own definition of `Symbol#to_proc`, for when it was supporting Ruby 1.8.x. The method we've talked in this chapter was added in 1.9.x. Rubinius also has its own definition.

- Rubinius: https://github.com/rubinius/rubinius/blob/v5.0/core/symbol.rb#L149-L152
- Rails 2.3:18: https://github.com/rails/rails/blob/v2.3.18/activesupport/lib/active_support/core_ext/symbol.rb


## Conclusion

A block created through `Symbol#to_proc` requires _at least_ one argument, the receiver of the method, and the remaining arguments must match the arity of the method identified by the symbol itself.

`even?` is parameter-less, `&:even?.to_proc` _must_ be called with one argument only, the receiver. `<=>` has an arity of one, `:<=>?.to_proc` _must_ be called with two arguments, the receiver, and the one and only argument to `<=>`.

Wondering how Ruby actually obtains the desired result given that it's dealing with nameless parameters? Let's look at the C definition of the method:

``` C
VALUE
rb_sym_to_proc(VALUE sym)
{
}
```

If you think it's weird, that's because it is!

In a next article, we'll look at how exactly Ruby handles block arguments when converting a symbol to a proc, we'll explore MRI's source code, in C, and peek at the grammar definition of the Ruby language. It's gonna be fun!

---

Found this interesting? You'll enjoy my [free online book][rebuilding-redis-in-ruby] about rebuilding Redis, in Ruby.

---

## Appendix, Arguments vs Parameters

As of recently I used to use the terms "arguments" and "parameters" interchangeably, and I was wrong.

Parameters are the variables that you can use from within a function/method/block, in the following example, `a` and `b` are parameters:

``` ruby
def a_method(a, b)
end
```

Arguments are the values that are given to a function/method/block, that will be be bound to the parameter variables. In the following example, `'a'` and `20` are arguments:

``` ruby
a_method('a', 20)
```

[twitter-etienne-barre]:https://twitter.com/BiHi
[rebuilding-redis-in-ruby]:https://redis.pjam.me/
[ruby-doc-sort]:https://ruby-doc.org/core-2.7.1/Enumerable.html#sort-method
[ruby-doc-spaceship-operator]:https://ruby-doc.org/core-2.7.1/Integer.html#3C-3D-method
[ruby-doc-symbol-to-proc]:https://ruby-doc.org/core-2.7.1/Symbol.html#to_proc-method
[ruby-doc-enumerable]:https://ruby-doc.org/core-2.7.1/Enumerable.html
[ruby-doc-even?]:https://ruby-doc.org/core-2.7.1/Integer.html#even-3F-method
[ruby-doc-map]:https://ruby-doc.org/core-2.7.1/Enumerable.html#map-method
[ruby-doc-parameters]:https://ruby-doc.org/core-2.7.1/Proc.html#parameters-method
