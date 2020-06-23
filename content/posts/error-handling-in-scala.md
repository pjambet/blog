---
title: "Error Handling in Scala"
date: 2020-01-04
lastmod: 2020-06-17T15:07:31-04:00
tags : [ "dev", "scala", "error handling" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: false
description: "After a few years of using Scala to develop backend services at Harry’s, we developed a robust approach to error handling, leveraging its powerful type system. It takes advantage of the flexibility of Scala types and tries to avoid defensive programming such as aggressive exception catching and re-throwing."
---

##### Previously published on [Medium](https://medium.com/@pierre_jambet/error-handling-in-scala-1197a742d6a5)

After a few years of using Scala to develop backend services at Harry’s, we developed a robust approach to error handling, leveraging its powerful type system. It takes advantage of the flexibility of Scala types and tries to avoid defensive programming such as aggressive exception catching and re-throwing.

It can be summarized as:

> Use non-exception types for expected errors and exceptions for unexpected errors. Let exceptions bubble all the way up to the edge of the system where they are caught and sent to an error aggregator service, such as Bugsnag or Sentry.

This article uses a simplified version of the Harry’s order service as a way to illustrate this approach.

## Context

This app is a small web service with a single endpoint allowing clients to create purchases, for the sake of simplicity the only element of the payload we are handling is a string, acting as a payment instrument. In a real world scenario things would be more complicated, the payload would need to include information about the products ordered and the payment data would need to be handled really carefully with regards to PCI compliance. Stripe’s new APIs, [Payment Methods](https://stripe.com/docs/payments/payment-methods) and [Payment Intents](https://stripe.com/docs/payments/payment-intents), which are [SCA compliant](https://stripe.com/docs/strong-customer-authentication), are great tools in that regard.

The code can be found on [Github](https://github.com/pjambet/scala-error-handling/tree/basic-version)

The two main requirements for this small project are the following:

- In order to allow API clients to display useful errors to users, non happy path scenarios, such as insufficient funds or invalid credit card zip code are explicitly handled and result in specific API error responses including detailed information.
- All other unexpected events, such as random network failures or failures from a third party API, result in a generic error. Having such handler as close as possible to the place where an API response is generated helps guarantee that any unhandled errors result in a generic 500 errors. No stacktrace or any other internal information should leak through the API.

The web API defines a single route, responding to a POST request:

```scala
val route =
  path("order") {
    post {
      entity(as[OrderRequest]) { orderRequest =>
        onComplete(PurchaseService.createPurchase(orderRequest)) {
          case Success(result) =>
            complete(StatusCodes.Created, result)
          case Failure(exception) =>
            system.log.error(exception, "Unexpected error")
            complete(StatusCodes.InternalServerError,
                     HttpEntity(ContentTypes.`application/json`,
                                "{\"error\": true}"))
        }
      }
    }
  }
```

{{< highlight scala >}}
object PurchaseService {

  final case class Order(orderId: String)

  def createPurchase(orderRequest: OrderRequest)(
      implicit ec: ExecutionContext): Future[Order] = {
    PaymentClient.chargeCard(orderRequest.cardNumber).map { _ =>
      Order(orderId = UUID.randomUUID().toString)
    }
  }
}
{{< / highlight >}}

```scala
object PaymentClient {

  final case class Charge(chargeId: UUID)

  sealed trait PaymentException extends Throwable
  final class CardException(val declineCode: String) extends PaymentException
  final class NotEnoughFundException extends PaymentException
  final class UnknownPaymentException extends PaymentException
  final class RateLimitException extends PaymentException
  final class InvalidRequestException extends PaymentException
  final class AuthenticationException extends PaymentException
  final class APIConnectionException extends PaymentException

  def chargeCard(cardNumber: String)(
      implicit ec: ExecutionContext): Future[Charge] = {
    // Fake an API call
    Future {
      if (cardNumber == "valid") {
        Charge(UUID.randomUUID())
      } else if (cardNumber.startsWith("card_error")) {
        val declineCode = cardNumber.split("_").toList.drop(2).mkString("_")
        throw new CardException(declineCode)
      } else if (cardNumber == "rate_limit_error") {
        throw new RateLimitException()
      } else if (cardNumber == "invalid_request_error") {
        throw new InvalidRequestException()
      } else if (cardNumber == "authentication_error") {
        throw new AuthenticationException()
      } else if (cardNumber == "api_connection_error") {
        throw new APIConnectionException()
      } else {
        throw new UnknownPaymentException()
      }
    }
  }
}
```

The happy path is reasonably straightforward, the JSON payload is parsed and the deserialized instance of the `Order` class is then passed to a service class, which will in turn delegate the API call to the payment gateway to a dedicated client class. If the payment goes through, we then return a successful response to the client. Once again we’re simplifying things quite significantly here, in a real world app, we would very likely want to do more, such as writing something to a database in order to have a record of the order.

The `chargeCard` method simulates how an exception-based client would behave, throwing different exceptions depending on which error occurred.

The `case Failure(exception) =>` clause handles any exceptions thrown within the Future returned by the createPurchase method.

---

Back to the main topic of this post, the situation starts to become more complicated once we start thinking about all the other outcomes beside the happy path. The following is a list of [all the errors documented in the API](https://stripe.com/docs/api/errors):



- api_connection_error: Failure to connect to Stripe's API.
- api_error: API errors cover any other type of problem (e.g., a temporary problem with Stripe's servers), and are extremely uncommon.
- authentication_error: Failure to properly authenticate yourself in the request.
- card_error: Card errors are the most common type of error you should expect to handle. They result when the user enters a card that can't be charged for some reason.
- idempotency_error: Idempotency errors occur when an Idempotency-Key is re-used on a request that does not match the first request's API endpoint and parameters.
- invalid_request_error: Invalid request errors arise when your request has invalid parameters.
- rate_limit_error: Too many requests hit the API too quickly.
- validation_error: Errors triggered by our client-side libraries when failing to validate fields (e.g., when a card number or expiration date is invalid or incomplete).

## Exception-only approach

The code we’re using is not actually making a request to Stripe, but it mimics the behavior of the official clients. Most of the official clients offer a generic and fairly non opinionated approach, using exceptions. The following is the official Java example, but most other languages look very similar, [Go being the exception](https://stripe.com/docs/api/errors/handling?lang=go) (pun intended):

```java
try {
  // Use Stripe's library to make requests...
} catch (CardException e) {
  // Since it's a decline, CardException will be caught
  System.out.println("Status is: " + e.getCode());
  System.out.println("Message is: " + e.getMessage());
} catch (RateLimitException e) {
  // Too many requests made to the API too quickly
} catch (InvalidRequestException e) {
  // Invalid parameters were supplied to Stripe's API
} catch (AuthenticationException e) {
  // Authentication with Stripe's API failed
  // (maybe you changed API keys recently)
} catch (APIConnectionException e) {
  // Network communication with Stripe failed
} catch (StripeException e) {
  // Display a very generic error to the user, and maybe send
  // yourself an email
} catch (Exception e) {
  // Something else happened, completely unrelated to Stripe
}
```

This approach is not inherently flawed, but it didn’t feel satisfying to us, for the following reasons:

- In Java, exceptions can be either checked or unchecked, but Scala treats all exceptions as unchecked, as illustrated [in this gist](https://gist.github.com/pjambet/44f017cfa2e332edfafc880f112d6d71). This means that it is impossible to rely on strong compile time checks to ensure that exceptions are handled.
- There is no hierarchy of errors, it is really hard to infer which exceptions should be caught and which one should not.

Following this architecture, it is non trivial to handle some errors differently than others. In the case of a `CardException`, we would like to extract the decline code from the error, and include it in the JSON response, so that a customer can see why the order failed. Being able to provide a detailed error, such as an invalid zip code, instead of a generic error can make a big difference both for the user, improving their experience, but also for the business. A user seeing a detailed error is more likely to know how to fix it and try again.

We now need to catch any potentially thrown exceptions to handle errors as previously explained. The following example shows how this can be achieved at the controller level.

```scala
case Failure(exception) =>
  exception match {
    case e: CardException =>
      complete(
        StatusCodes.PaymentRequired,
        HttpEntity(
          ContentTypes.`application/json`,
          "{\"message\": \"Invalid card\", \"code\": \"" + e.declineCode + "\"}"))

    case _ =>
      system.log.error(exception, "Unexpected error")
      complete(
        StatusCodes.InternalServerError,
        HttpEntity(ContentTypes.`application/json`,
                   "{\"message\": \"Something went wrong\"}"))
  }
```

This approach works fairly well here. When Stripe returns a card error, we include the decline code in the 412 Payment Required JSON response. All other errors result in a generic 500 Server Error response.

The controller code is now depending on lower level types, such as `CardException` , which is not ideal.

Furthermore, while we may have achieved the required goal in this particular situation, since the `chargeCard` method does not explicitly differentiate which exceptions are expected errors and which ones are unexpected, there is no way to rely on the compiler to make sure that all expected errors are handled in any other places where this method is called.

### Our solution

Looking at these errors closer we can separate them in in two distinct categories:

_Expected errors_, errors that cannot be prevented and therefore must be explicitly handled: *card_error*. While it might be interesting to have metrics to know how often these errors happen, observing them in a production environment, at a normal rate, *is not a bug* and does not warrant further investigation.

_Unexpected errors_, some of them being unpreventable, *api_connection_error* and *api_error*, and the ones caused by a bug or configuration error, aka errors that could be prevented: *authentication_error*, *idempotency_error*, *invalid_request_error* and *rate_limit_error*. In other words, observing the preventable ones in a production environment should be treated as a bug, probably urgent, and should be investigated.

For the unexpected errors that fall in the unpreventable category, there is not a lot of options in terms of actions that can be taken to address them. The few times we saw errors with the *api_error* code, we ended up reaching out to the Stripe support team to let them know about the issue.

The last error in the list, *validation_error*, is a little bit different since it’s never returned by the API but triggered from the client libraries and we will therefore ignore it.

This is what the code looks like with this approach:

```scala
final case class OrderRequest(cardNumber: String)
// ...
val route =
  path("order") {
    post {
      entity(as[OrderRequest]) { orderRequest =>
        onComplete(PurchaseService.createPurchase(
            orderRequest.cardNumber)) {
          case Success(Right(result)) =>
            complete(StatusCodes.Created, result)
          case Success(Left(error)) =>
            val jsonError =
              "{\"error\": \"" + error.reason + "\"}"
            complete(StatusCodes.PaymentRequired,
                     HttpEntity(ContentTypes.`application/json`,
                                jsonError))
          case Failure(exception) =>
            // This is where exceptions would ideally be sent
            // to a service like Bugsnag or Sentry
            system.log.error(exception, "Unexpected error")
            complete(
                StatusCodes.InternalServerError,
                HttpEntity(ContentTypes.`application/json`,
                  "{\"error\": \"Something went wrong.\"}"))
        }
      }
    }
  }
```

```scala
object PurchaseService {

  final case class Order(orderId: String)
  final case class PurchaseError(reason: String)

  def createPurchase(
      cardNumber: String)(implicit ec: ExecutionContext)
      : Future[Either[PurchaseError, Order]] = {
    PaymentClient.chargeCard(cardNumber).map {
      case Right(_)    => {
          Right(Order(orderId = UUID.randomUUID().toString))
      }
      case Left(error) => {
          Left(PurchaseError(error.declineCode))
      }
    }
  }
}
```

```scala
object PaymentClient {

  final case class Charge(chargeId: UUID)
  final case class ChargeFailure(declineCode: String)

  final class PaymentException(message: String) extends Exception(message)

  def chargeCard(cardNumber: String)(
      implicit ec: ExecutionContext): Future[Either[ChargeFailure, Charge]] = {
    // Fake an API call
    Future {
      if (cardNumber == "valid") {
        Right(Charge(UUID.randomUUID()))
      } else if (cardNumber.startsWith("card_error")) {
        val declineCode = cardNumber.split("_").toList.drop(2).mkString("_")
        Left(ChargeFailure(declineCode))
      } else if (cardNumber == "rate_limit_error") {
        throw new PaymentException("rate_limit_error")
      } else if (cardNumber == "invalid_request_error") {
        throw new PaymentException("invalid_request_error")
      } else if (cardNumber == "authentication_error") {
        throw new PaymentException("authentication_error")
      } else if (cardNumber == "api_connection_error") {
        throw new PaymentException("api_connection_error")
      } else {
        throw new PaymentException("unknown_error")
      }
    }
  }
}
```

The API client now returns a more complex type, which represents all the different outcomes that a caller _must_ handle. By virtue of explicitly being part of the return type, a caller will have to explicitly handle all the cases.

Note: The `PurchaseService` class defines its own type for the `Left` type returned by `createPurchase`. It may look unnecessary here since it is identical to the one defined in the payment client, but it is included to illustrate that in a real world example, the `Left` type might become more complex. We could imagine a parent trait PurchaseFailure with subtypes such as `case class InventoryUnavaible(skus: Set[Sku])`, `case class InvalidShippingAddress(zipcode: String)` etc …

This is a situation where the Scala type system really shines. We defined our return type as `Either[ChargeFailure, Charge]`, meaning that the compiler can check that all values have been accounted for when enumerating all the possible values. In practical terms, it means that callers of this method have to handle both sides, `Left` & `Right` when matching against the value of the method. Not doing so will emit a compile warning, which would be a compilation error with the `-Xfatal-warnings` flag.

Another benefit of this version, compared to the previous one, is that the semantics of the Stripe class do not leak through the rest of the app, the way it did with exceptions, where Stripe specific exceptions were handled in the controller.

Using exceptions for unexpected errors lets us write uncluttered code that doesn’t have to explicitly handle these truly exceptional cases. If the Stripe API returns an error due to an authentication error or a request being rate limited, there isn’t much that the user can do to. This is why we handle these cases by letting the generic handler return the “Something went wrong error”. Additionally, the error will be sent to an error aggregation service, Sentry in our case, to be addressed ASAP.

This `Either` based approach is conceptually very similar to [Railway Oriented Programming](https://fsharpforfunandprofit.com/rop/), which we will cover in details in another blog post.

The real payment service at Harry’s does not use the Stripe Java client. We ended up writing our own client, which uses the pattern described in this article.

We actually only use a small subset of the Stripe API endpoints: [Payment Intent creation](https://stripe.com/docs/api/payment_intents/create), [capture](https://stripe.com/docs/api/payment_intents/capture), [confirm](https://stripe.com/docs/api/payment_intents/confirm) and [Charge refund](https://stripe.com/docs/api/refunds/create) so writing our own client turned out to be a reasonably small task.

## Conclusion

- We use exceptions when the situation is truly exceptional, in its English sense, and do not expect callers to catch the exception, except the outer most layer of the program that makes sure a valid JSON response is returned through the API.
- Expected errors are handled through custom types, either with sealed traits, `Either` types or `Option` types. This article did not cover `Option` but there are a lot of articles on the topic, [this one](https://danielwestheide.com/blog/the-neophytes-guide-to-scala-part-5-the-option-type/) being one of my favorites. We also did not get to cover how `sealed trait` types can be used to rely on exhaustive pattern matching checks at compile times, which can also be very useful when defining methods with complex return types.
- When dealing with exception-throwing third parties, we catch these with a simple try/catch or a Try and convert the error to a custom type.
- Either types are great when operations can fail and can be used to implement a version of the “railway oriented programming” pattern.
- The `-Xfatal-warnings` compiler flags makes sure that all warnings are treated as errors at the compilation step. This makes sure that pattern matching checks are exhaustive and fail if a case is not handled.
- Writing your own API client can be worthwhile, despite the added development cost, as you end up having full control over its architecture and behavior. In this case, even though the Scala & Java interoperability allowed us to use an existing library, rolling out our own allowed us to take advantage of Scala specific features, providing better ergonomics for our needs.

Big thanks to [Brian Cobb](https://medium.com/u/b2c2765e632d) and [Ilya Rubnich](https://medium.com/u/a40221c8b9a6) for reviewing an early draft of this post.

Disclaimer: I am not currently employed by Harry’s, but worked on developing this approach to error handling while working there.
