---
title: "Railway Oriented Programming in Scala"
date: 2020-06-26T16:34:55-04:00
lastmod: 2020-06-26T16:34:55-04:00
tags : [ "dev", "scala", "error handling" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: false
summary: Playing with the Railway Oriented Programming concepts in scala. Translating them from F# and seeing how we can adapt them to Scala constructs. We will not mention Monads. I just did, but that was the only time.
---

![Train tracks](/images/rop-scala.jpg)

## Intro

A few years ago, a coworker introduced me to [Railway Oriented Programming (ROP)][rop-main-link]. At the time we were using Ruby, and while the ideas in ROP made a ton of sense, I didn't really find a way to apply them to what I was working on. The lack of types made it pretty hard to go beyond "I just read a blog post and I'm gonna pollute our codebase with it, because I can" and _actually_ improve things. We all pretty much moved on.

Later on, I switched to a different project, using Scala, and ended up using the `Either` type a lot, to organize what we described at the time as a "pipeline of operations", or maybe, something that one could describe as a ... "railway"?!

Anyway, we ended up using a bunch of similar constructs, but I never _really_ took the time to see how the actual, concrete elements of ROP could be used in Scala. I don't even work there anymore, and I haven't been paid to write Scala in about a year, but this is long overdue.

## Translating F# to Scala

Most, if not all, of this post is translating the code examples in the original ROP articles and presentations to Scala.

I have never written F#, not even "for fun", so it took me a while to get used to the syntax. It apparently uses a syntax similar to Haskell, another language I don't know much about, but I've started playing with Elm recently, also influenced by Haskell. Maybe that will help me decipher it.

The biggest barrier to readability for me is the "everything is curried" thingy. Let me pick an example from [the ROP blog post](https://fsharpforfunandprofit.com/posts/recipe-part2/):

``` fsharp
// validateInput : Request -> Result<Request,string>
let validateInput input =
   if input.name = "" then Failure "Name must not be blank"
   else if input.email = "" then Failure "Email must not be blank"
   else Success input  // happy path
```

`validateInput` is the function name and `input` is the argument, got it.

As much as Scala gets shit for being hard to read, very often well deserved, I find the following easier to understand. It might very well be because I did use Scala professionally and had to get use to it to do my job:

``` scala
def validateInput(input: Request): Result[Request, String] = {
  if (input.name == "") Failure("Name must not be blank")
  else if (input.email == "") Failure("Email must not be blank")
  else Success(input) // happy path
}
```

Both versions do the same thing, define a function, that accepts a single parameter, and they perform some validation on it.

Let's look at a function that takes two arguments now:

``` fsharp
let functionWithTwoParams paramOne paramTwo = paramOne + paramTwo
```

We keep adding new parameters after the function name, separated by spaces, the signature is:

``` fsharp
functionWithTwoParams : 'a -> 'b -> 'c
```

And we use it with

``` fsharp
functionWithTwoParams 1 2 // => 3
```

Because it's F#, and it apparently has the same benefits than Haskell in terms of generics support, we could use it with other types, not specifically ints, as long you can use `+`:

``` fsharp
functionWithTwoParams "a" "string" // => "astring"
```

What is interesting, and we're slowing getting to what I find confusing, is that you don't have to give the two arguments at once, and since functions are values, you can even assign it to another `let`, that's [the currying part](https://en.wikipedia.org/wiki/Currying):

``` fsharp
let anotherFunction = functionWithTwoParams "aString"
```

Full disclosure, I'm far from being an expert and my usage of "Currying" might have been inaccurate. The other closely related concept is ["Partial Application"](https://en.wikipedia.org/wiki/Partial_application). The two are not the same, but very much so related.

Anyway, what we get back is a value of type:

``` fsharp
string -> string
```

You can call the new function as you would call any other functions:

``` fsharp
anotherFunction "string" // => "astring"
```

What happened is that we provided the first argument in `'a -> 'b -> c`, so we ended up with the `'b -> 'c` part as a result. The compiler apparently inferred the `string` piece, based on the type of `"aString"`, and replaced `'b` and `'c`.

One way to think about it is as a chain of functions, each accepting one argument, and returning another function that takes one argument, that's essentially what the signature is with added parentheses for clarity:

``` fsharp
functionWithTwoParams : ('a -> ('b -> 'c))
```

For reference, this is the Scala equivalent, without generics, just `Int`, because, this is an article about ROP, not generics:

``` scala
def functionWithTwoParams(paramOne: Int, paramTwo: Int): Int  = paramOne + paramTwo
```

Which could be rewritten as the following, to be closer to the F# version, a function that returns another function, to illustrate the example mentioned above about the chain of functions:

``` scala
def functionWithTwoParams(paramOne: Int): Int => Int = { paramTwo: Int =>
  paramOne + paramTwo
}
```

``` scala
scala> functionWithTwoParams(1)
val res0: Int => Int = $Lambda$1033/0x00000008010e2840@627ff1b8

scala> res0(2)
val res1: Int = 3
```

But Scala also supports the curried/partial application approach, by separating the arguments in different parentheses groups:

``` scala
def functionWithTwoParams(paramOne: Int)(paramTwo: Int): Int =
  paramOne + paramTwo
```

``` scala
// We need the underscore to tell the compiler that we want a function
// Otherwise, you'd get the following error:
// error: missing argument list for method functionWithTwoParams [...]
scala> functionWithTwoParams(1) _
val res0: Int => Int = $Lambda$1032/0x00000008010e1840@5a90265a

scala> res0(2)
val res1: Int = 3
```

Now that we looked at how F# handles functions with multiples parameters, let's look at another example from ROP:

<!-- But I get confused, a lot, when you start adding more parameters, or returning a function, which, is apparently, essentially the same think in F# (Haskell, Elm, etc ... too). Let's look at another example: -->

``` fsharp
// bind : ('a -> Result<'b,'c>) -> Result<'a,'c> -> Result<'b,'c>
let bind switchFunction =
    fun twoTrackInput ->
        match twoTrackInput with
        | Success s -> switchFunction s
        | Failure f -> Failure f
```

This function, named `bind`, accepts one parameter, a function, `switchFunction`, which itself accepts one parameter, an `a` (`a` is a generic type here), and it returns a function, which itself accepts one parameter, named `twoTrackInput`, of type `Result<'a, 'c>` and returns a slightly different result: `Result<'b, 'c>`.

Let's look at the signature one more time:

``` fsharp
('a -> Result<'b,'c>) -> Result<'a,'c> -> Result<'b,'c>
```

This form is very similar to the two parameter function we defined above: `functionWithTwoParams`, and it shows that when you define a two parameter function, it is actually the same as if you defined a function that accepts one parameter and returns another function.

We can rewrite our function as:

``` fsharp
// functionWithTwoParams : 'a -> 'b -> 'c
let functionWithTwoParams paramOne =
  fun paramTwo ->
    paramOne + paramTwo
```

What makes this confusing to me is that, given that the return type is also a function, it also has a parameter, `Result<'a,'c>` and a return type `Result<'b,'c>`.

When you have a function that has the same signature as the parameter, you can pass it to `bind` and you get another function in return, again, not that different from what we did above when we partially applied `functionWithTwoParams`:

``` fsharp
let aNewFunction aValueOfTypeA = Failure "This function always fails"
bind aNewFunction // Returns a function that takes a Result as the parameter and returns another Result
```

Cool, cool, cool.

But we're not done here, as the article shows, we can rewrite `bind` as a two parameter function. For the same reason that we were able to write `functionWithTwoParams` in two different forms, one with a single parameter, where we were explicitly returning a new function, and one where we were accepting two parameters, the same holds for `bind`. Here is the two parameter version:

``` fsharp
let bind switchFunction twoTrackInput =
    match twoTrackInput with
    | Success s -> switchFunction s
    | Failure f -> Failure f
```

Let's wrap this up by looking at three Scala versions of `bind`:

The "naive" version, with two parameters:

``` scala
def bind[A, B, C](switchFunction: A => Result[B, C], twoTrackInput: Result[A, C]): Result[B, C] =
  twoTrackInput match {
    case Success(s) => switchFunction(s)
    case Failure(f) => Failure(f)
  }
```

The "function that returns a function" version:

``` scala
def bind[A, B, C](switchFunction: A => Result[B, C]): Result[A, C] => Result[B, C] = { twoTrackInput: Result[A, C] =>
  twoTrackInput match {
    case Success(s) => switchFunction(s)
    case Failure(f) => Failure(f)
  }
}
```

And finally, the curried/partial application version:

``` scala
def bind[A, B, C](switchFunction: A => Result[B, C])(twoTrackInput: Result[A, C]): Result[B, C] =
  twoTrackInput match {
    case Success(s) => switchFunction(s)
    case Failure(f) => Failure(f)
  }
```


### The ROP DSL

Now that we established how to translate some F# functions to Scala, let's get to it. The domain we're trying to replicate, copied from a mix of [the ROP slide deck](https://www.slideshare.net/ScottWlaschin/railway-oriented-programming) and [the blog post](https://fsharpforfunandprofit.com/posts/recipe-part2/), is the following:

1. We receive a request, it contains a name and an email
2. We validate the request
3. We update the DB based on the data in the request
4. We send an email
5. We return a message

Let's start with the request class:

``` scala
case class Request(name: String, email: String)
```

We now need to define the `TwoTrack` type (slide 97):

``` scala
sealed trait TwoTrackResult[S]

case class Success[S](data: S) extends TwoTrackResult[S]
case class Failure[S](message: String) extends TwoTrackResult[S]
```

Let's also add the two helper functions `succeed` and `failure`:

``` scala
def succeed[S](x: S) = Success(x)
def fail[S](message: String) = Failure[S](message)
```

### Validation

The validation is done in three steps, `nameNotBlank`, `name50` (checks that the name is not longer than fifty characters) and `emailNotBlank`. Using the types we just defined:

``` scala
def nameNotBlank(request: Request): TwoTrack[Request] =
  if (request.name == "") {
    fail("Name must not be blank")
  } else {
    succeed(request)
  }

def name50(request: Request): TwoTrack[Request] =
  if (request.name.length > 50) {
    fail("Name must not be longer than 50 chars")
  } else {
    succeed(request)
  }

def emailNotBlank(request: Request): TwoTrack[Request] =
  if (request.email == "") {
    fail("Email must not be blank")
  } else {
    succeed(request)
  }
```

In order to define `validateRequest`, we need to define `bind` (slides 92-96)

``` scala
def bind[A, B](switchFunction: A => TwoTrack[B])(twoTrackInput: TwoTrack[A]): TwoTrack[B] =
  twoTrackInput match {
    case Success(s) => switchFunction(s)
    case Failure(f) => fail(f)
  }
```

We can now define `validateRequest`:

``` scala
def validateRequest(twoTrackInput: TwoTrack[Request]): TwoTrack[Request] =
  (bind(nameNotBlank) _)
    .andThen(bind(name50))
    .andThen(bind(emailNotBlank))(twoTrackInput)
```

The `_` after `bind(nameNotBank)` is _really_ important, it forces the compiler to treat the value returned by `bind` as a function.

If we had defined `bind` with the following signature:

``` scala
def bind[A, B](switchFunction: A => TwoTrack[B]): TwoTrack[A] => TwoTrack[B]
```

we wouldn't have to use `_`. You can read more on the topic in this great article: ["Methods are not Functions"](https://tpolecat.github.io/2014/06/09/methods-functions.html)

### One-track functions

Let's continue through the slide deck, next, next, next, ok, stop, `canonicalizeEmail` is the next function, that's on slide 103:

``` scala
def canonicalizeEmail(request: Request): Request =
  request.copy(email = request.email.trim().toLowerCase())
```

We now need to define `map` to transform `canonicalizeEmail`, the deck shows two different versions (slides 107 & 108):

``` scala
def map[A, B](singleTrackFunction: A => B): TwoTrack[A] => TwoTrack[B] = { twoTrackInput: TwoTrack[A] =>
  twoTrackInput match {
    case Success(s) => succeed(singleTrackFunction(s))
    case Failure(f) => fail(f)
  }
}
```

The `map` function accepts one parameter, a function that goes from `A` to `B`, and returns another function, one that goes from `TwoTrack[A]` to `TwoTrack[B]`. In order to do that, we return a new function, that itself accepts one parameter, a `TwoTrack[A]`. This returned function does one thing, it looks at the type of its argument, if it is a `Failure`, it recreates an other failure, effectively continuing on the red track.

If it is a `Success`, it extracts the value from it, through pattern matching, and passes that value, an `A`, to the single track function, the function we're adapting to a two track world, and we wrap the result in a success. Essentially, we continue on the green track, while applying the function, changing the value type from `A` to `B`. It is important to note that `A` and `B` can be the same type, and this is what will happen when we wire everything together later on, both `A` and `B` will be `Request`.

The second version offered in the deck is:

``` scala
def map[A, B](singleTrackFunction: A => B): TwoTrack[A] => TwoTrack[B] =
  bind(singleTrackFunction.andThen(succeed))
```

Woof, that escalated quickly. I feel like I need to stop and look closer because at first glance, it makes no sense to me. Let's start with the inner part, what is given to `bind`:

``` scala
singleTrackFunction.andThen(succeed)
```

It creates a new function, this function will first call `singleTrackFunction`, with an `A`, and then pass the result to `succeed`, which creates a new instance of `Success[A]`, we can see that in a repl, where I first define an arbitrary `singleTrackFunction`:

``` scala
scala> val singleTrackFunction: String => Int = str => str.length
val singleTrackFunction: String => Int = $Lambda$1175/0x0000000801160040@65bd19bf

scala> singleTrackFunction.andThen(succeed)
val res0: String => Success[Int] = scala.Function1$$Lambda$1192/0x000000080116a040@322b09da
```

In this example, `singleTrackFunction` is a function that takes a `String` and returns its length, a function that goes from `String` to `Int`. The result of `singleTrackFunction.andThen(succeed)` is another function, that goes from `String` to `Success[Int]`, we can call with with any `String`:

``` scala
scala> res0("aString")
val res1: Success[Int] = Success(7)
```

`res0` has a signature that matches the `switchFunction` argument of `bind`, so when we pass `singleTrackFunction.andThen(succeed)` to `bind`, we receive a `TwoTrack[String] => TwoTrack[Int]` function back.

With `map`, we can transform `canonicalizeEmail` into a function that can be chained after `validateRequest`, we can now write

``` scala
(validateRequest _).andThen(map(canonicalizeEmail))
```

### Dead end functions & exception handling

Next on a list, "Dead-End Functions".

We will ignore the actual implementation of `updateDB`, it is irrelevant to this post. Regardless of the DB you use, the function would call some form of db access layer, and then use an SDK or a library to communicate with the DB and store the data. The point is that the input is a request and that we are writing this function wide side-effects. Things would be different if we wanted to use a more functional approach, with no, or less, side effects. Libraries like [Cats Effect](https://typelevel.org/cats-effect/) and [Monix](https://monix.io/) help with that, and using these with ROP could be an interesting discussion, but one that is out of scope at the moment.

The next function on our list is `tee`:

``` scala
def tee[A](deadEndFunction: A => Unit)(a: A): A = {
  deadEndFunction(a)
  a
}
```

We can use it with `updateDB` to append it to the `validateRequest` & `canonicalizeEmail` railway we started above. The trick is that we also need to use `map`. Calling `tee` with `updateDB` gives us a single track function, which is not enough to plug it in, `map` transforms the newly created single track function as the result of `tee` to a two-track function:

``` scala
def updateDB(request: Request): Unit = ()
// ...
(validateRequest _)
  .andThen(map(canonicalizeEmail))
  .andThen(map(tee(updateDB)))
```

But what if `updateDB` throws an exception, one from the underlying SDK that it would use if it was a real function, let's catch exceptions and transform them into `Failure` instances.

Note: this part is not in the slide deck, it is taken from [the blog post](https://fsharpforfunandprofit.com/posts/recipe-part2/).

We're copying the following function:

``` fsharp
let tryCatch f exnHandler x =
    try
        f x |> succeed
    with
    | ex -> exnHandler ex |> fail
```

and translating it to:

``` scala
def tryCatch[A, B](f: A => B)(exnHandler: Throwable => String)(x: A): TwoTrack[B] = try {
  succeed(f(x))
} catch {
  case ex: Throwable =>
    fail(exnHandler(ex))
}
```

We can now use `tryCatch` to wrap `updateDB`, and our railway now looks like the following:

``` scala
val updateDBStep: Request => TwoTrack[Request] =
  tryCatch(tee(updateDB))(ex => ex.getMessage)

(validateRequest _)
  .andThen(map(canonicalizeEmail))
  .andThen(bind(updateDBStep))
```

### Adding `log`

The last part is about what is called "Supervisory functions" in the deck, e.g. logs:

We first need `doubleMap`, defined as:

``` fsharp
let doubleMap successFunc failureFunc twoTrackInput =
    match twoTrackInput with
    | Success s -> Success (successFunc s)
    | Failure f -> Failure (failureFunc f)
```

which gives us the following once translated:

``` scala
def doubleMap[A, B](successFunc: A => B)
                   (failureFunc: String => String)
                   (twoTrackInput: TwoTrack[A]): TwoTrack[B] = twoTrackInput match {
  case Success(s) => succeed(successFunc(s))
  case Failure(f) => fail(failureFunc(f))
}
```

We're really close, one more step, `log`:

``` fsharp
let log twoTrackInput =
    let success x = printfn "DEBUG. Success so far: %A" x; x
    let failure x = printfn "ERROR. %A" x; x
    doubleMap success failure twoTrackInput
```

and in scala:

``` scala
def log[A](twoTrackInput: TwoTrack[A]): TwoTrack[A] = {
  val success = { x: A => println(s"DEBUG. Success so far: $x"); x }
  val failure = { x: String => println(s"ERROR. $x"); x }
  doubleMap(success)(failure)(twoTrackInput)
}
```

And with this, we can add `log` to our railway:

``` scala
(validateRequest _)
  .andThen(map(canonicalizeEmail))
  .andThen(bind(updateDBStep))
  .andThen(log)
```

### Final touches

This railway returns a function, which accepts a `TwoTrack[Request]` as the input, which we can manually construct and feed to it as such:

``` scala
val railway = (validateRequest _)
  .andThen(map(canonicalizeEmail))
  .andThen(bind(updateDBStep))
  .andThen(log)

val request = Request(name = "Pierre", email = "hello@pjam.me")

railway(succeed(request))
// or explicitly calling .apply
railway.apply(succeed(request))
```

I didn't love that we had to create a success like that, so I added `succeed` at the beginning of the pipeline instead:

``` scala
val railway = (succeed[Request] _)
  .andThen(validateRequest)
  .andThen(map(canonicalizeEmail))
  .andThen(bind(updateDBStep))
  .andThen(log)

val request = Request(name = "Pierre", email = "hello@pjam.me")

railway(request)
// or explicitly calling .apply
railway.apply(request)
```

You may have noticed that we did not include `sendEmail` in the railway. I don't think it adds much to what we already have, and we've already written a lot of code so far, I didn't want to add anything unnecessary. It's also worth mentioning that my post was based on two sources, the slide deck and the blog post, and only the deck includes the `sendEmail` piece.

### Didn't we basically re-implement Either

Yes, kinda! And this is mentioned [on the ROP page as well](https://fsharpforfunandprofit.com/rop/#relationship-to-the-either-monad-and-kleisli-composition) (with a mention of Haskell, not Scala, sorry folks). The Scala [cats](https://typelevel.org/cats/) library provides [a Kleisli class](https://typelevel.org/cats/datatypes/kleisli.html), which is conceptually extremely similar to the ROP approach.

EDIT: Thanks to [paul_f_snively](https://www.reddit.com/user/paul_f_snively/) on [/r/scala](https://www.reddit.com/r/scala/comments/ho33fy/railway_oriented_programming_in_scala/) for pointing me to the Kleisli class.

So, did we need to do this? Maybe, I don't know, I for one learned a lot working through this translation exercise.

I wanted to see what an `Either` based version would look like, this is what I ended up with:

``` scala
import scala.util.{Failure, Success, Try}

object RailwayEither {

  type TwoTrack[S] = Either[String, S]

  def fail[S](message: String) = Left(message)

  def succeed[S](x: S) = Right(x)

  def switch[A, B](fn: A => B): A => TwoTrack[B] =
    fn.andThen(Right.apply)

  def tryCatch[A](fn: A => Unit)(x: A): Either[String, A] = {
    Try(fn(x)) match {
      case Failure(exception) => Left(exception.getMessage)
      case Success(_) => Right(x)
    }
  }

  case class Request(name: String, email: String)

  def nameNotBlank(request: Request): TwoTrack[Request] =
    if (request.name == "") {
      fail("Name must not be blank")
    } else {
      succeed(request)
    }


  def name50(request: Request): TwoTrack[Request] =
    if (request.name.length > 50) {
      fail("Name must not be longer than 50 chars")
    } else {
      succeed(request)
    }


  def emailNotBlank(request: Request): TwoTrack[Request] =
    if (request.email == "") {
      fail("Email must not be blank")
    } else {
      succeed(request)
    }


  def validateRequest(twoTrackInput: TwoTrack[Request]): TwoTrack[Request] = {
    for {
      r <- twoTrackInput
      r <- nameNotBlank(r)
      r <- name50(r)
      r <- emailNotBlank(r)
    } yield r
  }

  def updateDB(request: Request): Unit = {
    //    throw new RuntimeException("Fake DB Error")
    ()
  }

  def canonicalizeEmail(request: Request): Request = {
    request.copy(email = request.email.trim().toLowerCase())
  }

  def logSuccess[A](x: A): TwoTrack[A] = {
    println(s"DEBUG. Success so far: $x");
    succeed(x)
  }

  def logFailure[A](x: String): TwoTrack[A] = {
    println(s"ERROR. $x");
    fail(x)
  }

  def main(args: Array[String]): Unit = {

    val request = Request(name = "Pierre", email = "pierre@pjam.me")

    val updateDBStep: Request => TwoTrack[Request] = tryCatch(updateDB)

    val railway = validateRequest(succeed(request))
      .flatMap(switch(canonicalizeEmail))
      .flatMap(updateDBStep)
      .fold(logFailure, logSuccess)

    println(railway)
  }
}
```

It is _a_ solution, and I'm sure we could do better, but it was an attempt to highlight how we could replicate something similar with the standard library. Feedback welcome! I'm on Twitter, [@pierre_jambet](https://twitter.com/pierre_jambet)

What comes next? Not sure, there are a bunch of things I could try to play with, like the parallel error handling thing, not to be confused with parallel in the async sense. Parallel in this context means applying all the validations independently of each other, instead of doing it sequentially. Validating a request with an empty name and empty email will only return the first error with this implementation. It would be nice if it returns two, one for the email error, one for the name error.

All the code in this article is available [in this Gist](https://gist.github.com/pjambet/6d5aa587b29f9c4635bb7ed7796cb0b9)

In the [following post](/posts/railway-oriented-programming-scala-parallel/) we explore parallel validation. See you there!


Sources:

- https://fsharpforfunandprofit.com/rop/ : The main ROP page
- https://fsharpforfunandprofit.com/posts/recipe-part2/ : The "blog post" reference through this post
- https://www.slideshare.net/ScottWlaschin/railway-oriented-programming : The slide deck referenced through this post
- https://github.com/jorander/ScalaROP : A scala package providing most of the DSL presented here

[rop-main-link]:https://fsharpforfunandprofit.com/rop/
