---
title: "Parallel Validation for Railway Oriented Programming in Scala"
date: 2020-07-15T15:37:55-04:00
lastmod: 2020-07-15T15:37:55-04:00
tags : [ "dev", "scala", "error handling" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: false
summary: A followup to the previous post about Railway Oriented Programming in Scala, adding support for parallel validations
---

## Combining functions in parallel

In the [previous post](/posts/railway-oriented-programming-scala/) we attempted to translate the main F# constructs from the original Railway Oriented Programming (ROP) documents — the [blog posts](https://fsharpforfunandprofit.com/series/a-recipe-for-a-functional-app.html) & the [slide deck](https://www.slideshare.net/ScottWlaschin/railway-oriented-programming) — to Scala. We also showed what an alternative approach looks like using Scala's built-in [`Either`](http://www.scala-lang.org/api/2.13.3/scala/util/Either.html) class. The code is [on GitHub](https://gist.github.com/pjambet/6d5aa587b29f9c4635bb7ed7796cb0b9). That was a long article, I want to keep this one short & sweet.

In this article, we will focus on the ["Combining functions in parallel"](https://fsharpforfunandprofit.com/posts/recipe-part2/#combining-functions-in-parallel) piece.

The word "parallel" in this context can be confusing because it is conceptually different from "parallelism" used to describe tasks happening at the same time. What we mean here is closer to the concept of a [parallel circuit](https://en.wikipedia.org/wiki/Series_and_parallel_circuits#Parallel_circuits).


## The problem

Using the train track analogy, when we ran the `validateRequest` function in the previous post, the switches were arranged sequentially, or in series.

In concrete terms, it means that the input, a `Request` instance, was first passed to the `nameNotBlank` function, depending on whether or not the `name` field was blank, it returns a `Success` or a `Failure`. If it's a failure, the next function in the chain will be bypassed, that's what `bind` does. One way to illustrate this is to rewrite `validateRequest`, but instead, inline all the function calls, it's verbose, but make the overall behavior a bit more explicit:

``` scala
def validateRequest_inlined(input: Request): TwoTrack[Request] = {
  // nameNotBlank
  val nameNotBlankResult = if (input.name == "") {
    fail("Name must not be blank")
  } else {
    succeed(input)
  }

  nameNotBlankResult match {
    case Success(s) => {
      // name50
      val name50Result = if (s.name.length > 50) {
        fail("Name must not be longer than 50 chars")
      } else {
        succeed(s)
      }

      name50Result match {
        case Success(s) => {
          // emailNotBlank
          if (s.email == "") {
            fail("Email must not be blank")
          } else {
            succeed(s)
          }
        }
        case Failure(f) => fail(f)
      }
    }
    case Failure(f) => fail(f)
  }

}
```

The `emailNotBlank` or `name50` logic will never run if the `name` field is blank. It's not a problem for `name50`, because a name cannot be longer than 50 characters and be blank at the same time, but if the input is `Request("", "")`, it would be nice if the response included the two errors with it, indicating that both `name` & `email` are blank.

## A solution

We will look at two different approaches, with the first one we'll keep the underlying data of the `Failure` class a `String`, but after looking at the limitations, we will look at another approach, using a list of errors.

First, we need the `plus` function:

``` fsharp
let plus addSuccess addFailure switch1 switch2 x =
    match (switch1 x),(switch2 x) with
    | Success s1,Success s2 -> Success (addSuccess s1 s2)
    | Failure f1,Success _  -> Failure f1
    | Success _ ,Failure f2 -> Failure f2
    | Failure f1,Failure f2 -> Failure (addFailure f1 f2)
```

Translated to scala, we end up with:

``` scala
def plus[A, B](addSuccess: (B, B) => B,
               addFailure: (String, String) => String,
               switch1: A => TwoTrack[B],
               switch2: A => TwoTrack[B])
              (x: A): TwoTrack[B] = {
  (switch1(x), switch2(x)) match {
    case (Success(s1), Success(s2)) => Success(addSuccess(s1, s2))
    case (Failure(f1), Success(_)) => Failure(f1)
    case (Success(_), Failure(f2)) => Failure(f2)
    case (Failure(f1), Failure(f2)) => Failure(addFailure(f1, f2))
  }
}
```

Note: I don't know about you, but the type annotations _really_ help me decipher what's happening with this whole thing, especially with the function parameters.

I hope that my scala version is readable, but for the sake of explicitness, this is what it does translated to plain English:

> Given a function that combines two successes, of generic type `B`, and returns a single `B`, a function that combines two failures, each represented as a `String`, and returns a single `String`, and two switch functions with identical signatures (`A => TwoTrack[B]`), return a function that takes an `A` and return a `TwoTrack[B]`, also known as a switch function, from `A` to `B`.

With `plus`, we can now write `&&&`, which combines two switches into one:

``` fsharp
let (&&&) v1 v2 =
    let addSuccess r1 r2 = r1 // return first
    let addFailure s1 s2 = s1 + "; " + s2  // concat
    plus addSuccess addFailure v1 v2
```

and in Scala:

``` scala
def &&&[A, B](v1: A => TwoTrack[B], v2: A => TwoTrack[B]): A => TwoTrack[B] = {
  val addSuccess: (B, B) => B = (r1: B, r2: B) => r1
  val addFailure: (String, String) => String = (s1: String, s2: String) => s"$s1; $s2"
  plus(addSuccess, addFailure, v1, v2)
}
```

We give it two switch functions, it returns a single one. In order to do that, it first creates two functions `addSuccess` & `addFailure`. `addSuccess` ignores the second parameter and returns the first one. The idea being that we'll use `&&&` in places where each switch function, `v1` & `v2` will receive the same argument, so `r1` and `r2` will be identical, we just pick one. `addFailure` concatenates the two strings with a semi colon in the middle, to keep it nicely formatted.

We can now rewrite `validateRequest`:

``` scala
def validateRequest: Request => TwoTrack[Request] =
  &&&(&&&(nameNotBlank, name50), emailNotBlank)
```

Note: A previous version of `validateRequest` had the signature `TwoTrack[Request] => TwoTrack[Request]` but I later realized that it could be simplified to `Request => TwoTrack[Request]`.

And it works!, it has the signature as before, so we can just replace it in the `railway` val in the `main` method and we now have parallel validations, let's look at an example:

``` scala
val railway =
  validateRequest
    .andThen(map(canonicalizeEmail))
    .andThen(bind(updateDBStep))
    .andThen(log)

val request = Request(name = "", email = "")

railway(request)
```

It prints the following error, the result of concatenating the errors returned by `nameNotBlank` & `emailNotBlank`:

```
ERROR. Name must not be blank; Email must not be blank
```

### Final touches

We're pretty much done feature wise, but we can do better.

The first thing I want to improve is the way we use `&&&`, if you're a lisper it might not shock you, after all prefix notation is the only real way to write code and everything else is inferior. If you're like me and that's not how you think, you might want a syntax similar to what Scott did in F#, something like: `validate1 &&& validate2 &&& validate3`. We can do that in Scala, but we have to jump through a few hoops, my solution is almost directly copied from [ScalaROP](https://github.com/jorander/ScalaROP/blob/master/src/main/scala/jorander/scalarop/package.scala#L27-L29):

``` scala
sealed case class ComposableSwitch[A, B](v1: A => TwoTrack[B]) {
  def &&&(v2: A => TwoTrack[B]): A => TwoTrack[B] = {
    val addSuccess: (B, B) => B = (r1: B, _: B) => r1
    val addFailure: (String, String) => String = (s1: String, s2: String) => s"$s1; $s2"
    plus(addSuccess, addFailure, v1, v2)
  }
}

implicit def functionToComposableFunction[A, B](f: A => TwoTrack[B]) = ComposableSwitch(f)

def validateRequest: Request => TwoTrack[Request] =
  nameNotBlank &&& name50 &&& emailNotBlank
```

Don't ask me too many questions about the implicit thing, it stills kinda feels like black magic to me. And I'm sure that there are slightly different alternatives with potential benefits, but the idea here is that we define a class that defines a `&&&` method, and an implicit `def` that will instantiate this class for us, so essentially this is what the new `validateRequest` does under the hood:

``` scala
def validateRequest: Request => TwoTrack[Request] =
  ComposableSwitch(ComposableSwitch(nameNotBlank).&&&(name50)).&&&(emailNotBlank)
```

### Replacing `String` with a `List[String]` in `Failure`

The `Failure` class is a wrapper around a `String` instance. When combining two errors, if we want to prevent any loss of information, we don't have any other options but to concatenate the strings. This is not a problem per-se but it seriously impedes the usability of the `Failure` class.

Imagine that this railway was used in a web application — this is the use case for this all exercise after all — and imagine that we would use the result in two different contexts. The first one is an HTML response, and we would want to include a concatenated string on the page, the second one is a JSON API, where we would want to return an array of errors.

The current implementation lets us implement the first use case for the HTML page generation but makes the second one more complicated. We _could_ decide to split the string on `;` and trim each parts to remove white spaces but this feels wasteful. We initially started with individual strings, we then concatenated them and now we would have to split it.

Let's improve this by replacing our `Failure` class with the following:

``` scala
case class Failure[S](messages: List[String]) extends TwoTrack[S]
```

We now have to change a bunch of things throughout our other functions to accommodate for this change. You can see the updated version [on GitHub](https://gist.github.com/pjambet/06d76e662550bfa1f54260500626f46f), but we have more flexibility when using the result. The HTML generating part will concatenate the strings from the list, and the JSON API can write JSON array from the `List[String]` instance. We essentially postponed the creation of the concatenated string to when it is actually needed and we kept a more "raw" representation with the list.

This approach is inspired by [this great talk](https://www.youtube.com/watch?v=GqmsQeSzMdw) from Rúnar Bjarnason, where he compares abstractions with TNT in Minecraft. I've never played Minecraft, but I like the analogy!

## Conclusion

The code is [on GitHub](https://gist.github.com/pjambet/06d76e662550bfa1f54260500626f46f)

We explored how to apply parallel validations and return all the validations that apply to a given input, instead of stopping as soon as we encounter the first error.

We also briefly looked at the limitations of using a single `String` as the data backing a `Failure` instance, and how using a `List[String]` gives us more flexibility.

In a later article we'll look at the `NonEmptyList` type, available [in cats](https://typelevel.org/cats/datatypes/nel.html) and how it would improve our `Failure` class. An empty `List` instance for a `Failure` doesn't make any sense. Why would it be a failure if it doesn't have any errors. `NonEmptyList` prevents that. Stay tuned!
