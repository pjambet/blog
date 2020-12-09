---
title: "Intent Pattern"
date: 2020-12-08T11:39:45-05:00
lastmod: 2020-12-08T11:39:45-05:00
tags : [ "dev", "design patterns" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: false
summary: "A design pattern for robust handling of resource creation on a remote service."
---

**This article is a draft**

**tl;dr;** Before creating a resource with an upstream service, first persist an intent record, acting as an empty shell for the future resource identifier. Once the resource is created on the upstream service, finalize the intent record by updating it with a unique identifier.

## Nomenclature

This pattern is useful when two services communicate with each other. The service initiating the request is referred to as the "client" and the service receiving the request is referred to as the "server". Note that you may or may not own the server in this context. It may be a third party API, such as Stripe, or GitHub, or another service owned and operated by your organization.

## Problem

Creating resources remotely is a common operation, being in a micro-services world or not, and handling all the edge cases outside the happy path can be tricky. Errors are unavoidable, common causes are timeout or network related. The main problem is the creation of "dangling resources", a resource successfully created on the server, without the client being aware of it.

The following sequence illustrates the problem:

1. The client sends an http request to the server to create a new resource
2. The server processes the request and creates the resource
3. A network error occurs and the client loses the connection to the server
4. Depending on the nature of the network error, the server may or may not notice the connection error, but regardless, the client is now unaware of the existence of the new resource

**The intent pattern solves this problem by always creating an intent record before initiating the creation of the resource.** 

## Sequence Diagram

{{< mermaid >}}
sequenceDiagram
    participant Client
    participant Server
    Client->>Client: Persist intent record
    Client->>+Server: Create resource request
    Server-->>-Client: Newly created resource
    Note right of Server: Resources must include a unique identifier
    Client->>+Client: Update intent record with the unique identifier
{{< /mermaid >}}

## Description

Persisting an intent record before issuing an HTTP call for the creation of a resource with a server guarantees that the client can act as the source of truth. The following is an exhaustive list of all the possible scenarios:

1. Happy path: no errors happen, the resource is created and the intent record is updated with the resource id
2. Failure to create the intent record: something goes wrong when attempting to persist the intent record. No resource is ever created and an error is returned to the client
3. Server error: the intent record is created, but something happened with the server. Depending on the nature of the error, the client might receive an error, and can update the intent record to flag it as "dead" or may not receive anything back from the server. In this case the data should be reconciliated. More on that below. 
4. Network error: the intent record is created, but due to a network error, the client never receives the resource object. There is now a consistency issue and the issue should ideally be reconciliated. More on that below.
5. Failure to update the intent record: both the intent record and the resource were created, but something happened, preventing the update of the intent record. There is now a consistency issue and the issue should ideally be reconciliated. More on that below.

Following this pattern, if an intent record exists, it is extremely likely that the server received and processed a resource creation request, but technically not guaranteed, as shown with examples 3) and 4) above. Additionally, it is _guaranteed_ that an intent record exists for every resource creation request processed by the server.

### Data reconcilation

Handling dangling records is application specific and the requirements will vary from one app to another. Regardless of such requirements, this pattern gives you a great starting point to guarantee the consistency of your system by being able to identify which records have been finalized and which ones have not.

An intent record that is never finalized, that is updated with a resource identifier, should be considered "dead".

Applications should allow for a grace period to treat an intent record as "dead". The creation process on the server might take a few seconds, so an intent record five second old without an identifier should probably not be considered dead. On the other hand, the same record should very likely be considered dead after a week. 

## Example: Charge a customer with Stripe

> The term "intent" gets overloaded between Stripe's PaymentIntent object and the "intent record" described in this article, but we started using this term in 2016, a few years before the introduction of the PaymentIntent APIs, back when [Charges](https://stripe.com/docs/payments/charges-api) were the recommended option.

Charging a customer with Stripe, in its simplest form, requires an API call like the following to the ["Create PaymentIntent" API](https://stripe.com/docs/api/payment_intents/create):

```bash
$ curl https://api.stripe.com/v1/payment_intents \
  -u sk_XXX_YYY: \
  -d amount=2000 \
  -d currency=usd \
  -d confirm=true \
  -d "payment_method_types[]"=card \
  -d "payment_method"=pm_card_visa
```

It is a good practice to use timeouts when issuing HTTP requests, it could otherwise lead to requests hanging for a very long time, causing a long wait for user and potential performance issues with the application. Last I checked this particular request had a p95 around 4.5s, but it was not uncommon to see requests taking more than 20 or 25s.

The following diagram illustrates a subset of the sequence of events occuring when a customer places an order. Other elements, such as anything related to the fulfillment of the order, checking if items are in stock is purposefully ignored.

{{< mermaid >}}
sequenceDiagram
    participant User
    participant Browser
    participant Rails App
    participant Stripe
    User->>+Browser: Click "Place Order"
    Browser->>+Rails App: Order request
    Rails App->>+Stripe: PaymentIntent request
    Stripe-->>-Rails App: New PaymentIntent object
    Rails App-->>-Browser: New order
    Browser-->>-User: Order placed!
{{< /mermaid >}}

At the time our Ruby client was configured with a 20s timeout, meaning that we would occasionally see timeout errors, not _that_ often, but up to a few times a week.

---

It turns out that the impact of a PaymentIntent being created on Stripe while not being acknowledged by the client is not too bad for the customer. PaymentIntents must be captured for the funds to actually be transferred out of the customer's account. Because Harry's is an e-commerce business shipping physical goods, we would only perform the capture when shipping the goods. A dangling PaymentIntent would never have been captured since the order attached to it would have never seen the successful charges. Such PaymentIntents would end up automatically refunded after seven days.

These customers would see a pending charge on their bank account for up to a week, which is not desirable, and may look odd to them if they see multiple pending transactions.

---

### Code example:

We first need a table to store the intent records for customer payments:

```sql
CREATE TABLE customer_payments(
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id),
    stripe_payment_intent_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

The following code is an example of the intent pattern in action, first write a row, then create the resource and finally update the intent record.

```ruby
def charge_customer(customer_id, amount, currency, payment_method)
  record = persist_intent_record(customer_id)
  stripe_payment_intent = create_payment_intent(amount, currency, payment_method)
  update_intent_record(record.id, stripe_payment_intent.id)
end

def persist_intent_record(customer_id)
  # ActiveRecord or similar to insert into customer_payments
  # INSERT INTO customer_payments (customer_id) VALUES (?)
end

def update_intent_record(id, stripe_payment_intent_id)
  # ActiveRecord or similar to update a row from into customer_payments:
  # UPDATE customer_payments SET stripe_payment_intent_id = ? WHERE id = ?;
end

def create_payment_intent(amount, currency, payment_method)
  Stripe::PaymentIntent.create({
    amount: amount,
    currency: currency,
    confirm: true,
    payment_method_types: ['card'],
    payment_method: payment_method,
  })
end
```

### One step further, idempotency

This pattern was developped organically at Harry's as we were building new services, but it happens to be a subset of the idempotency pattern described in ["Implementing Stripe-like Idempotency Keys in Postgres"][idempotency-article].

In the example above, it would be very helpful to pass an idempotency key to the create PaymentIntent API call. There is more than one way of achieving this. A naive approach would be to use the `id` attribute returned from the insertion to the `customer_payments` table, after all it is guaranteed to be unique. This would look like the following:

```bash
$ curl https://api.stripe.com/v1/payment_intents \
  -u sk_XXX_YYY: \
  -H "Idempotency-Key: DATABASE_ID" \
  -d amount=2000 \
  -d currency=usd \
  -d confirm=true \
  -d "payment_method_types[]"=card \
  -d "payment_method"=pm_card_visa
```

If exposing an internal value like an auto-incremented id to the outside world is problematic[^1], you could use something else, such as a randomly generated uuid. This might not be a problem if you use a uuid instead of a sequence as the primary key.

Regardless of the implementation detail, using an idempotency here can help for the reconciliation step. It would let you retry requests without risking creating a new payment intent.

The idempotency key might not be enough if what you want is finding the dangling resource. In order to do so, you would need to use the ["List all PaymentIntents" endpoint](https://stripe.com/docs/api/payment_intents/list?lang=curl), but we wouldn't have an easy way to find which PaymentIntent is the one created for our intent record. Stripe allows for [Metadata](https://stripe.com/docs/api/metadata?lang=curl) to be passed:

```bash
$ curl https://api.stripe.com/v1/payment_intents \
  -u sk_XXX_YYY: \
  -H "Idempotency-Key: DATABASE_ID" \
  -d amount=2001 \
  -d currency=usd \
  -d confirm=true \
  -d "payment_method_types[]"=card \
  -d "payment_method"=pm_card_visa \
  -d "metadata[order_id]"=order-DATABASE_ID
```

With metadata attached to PaymentIntents, we would now be able to list all of the PaymentIntents created around the creation time of our `customer_payments` row missing a payment intent id, and inspect the `order_id` metadata of PaymentIntent objects returned from the Stripe API to find the dangling one.

The intent pattern gives you a strong foundation to adapt to various business requirements and [the article][idempotency-article] mentioned above is a great deep dive on the topic of idempotency.

## Conclusion

The patterns described in this article and in ["Implementing Stripe-like Idempotency Keys in Postgres"][idempotency-article] focus on payment with Stripe, but this pattern has more applications.

Another use case could be for sending emails. Instead of directly handling the process of sending emails, some companies rely on third-party APIs, where the process of sending email is an HTTP call with an email template id and some interpolation variables. In this case, it might be important to know whether or not an email was sent, and the intent pattern can help in the same way it helped with Stripe payments.

---

Efficiently Fetching dangling records can be problematic, especially with large tables. In the example above, the following query, which will fetch all the dead records created more than two days ago, will become really slow as the table grows given that both columns are unindexed. 

```sql
SELECT *
FROM customer_payments
WHERE stripe_payment_intent_id IS NULL
AND created_at >= NOW() - INTERVAL '48 HOURS'
```


A solution to this problem is to add an index to `stripe_payment_intent_id`:

```sql
CREATE INDEX cus_pay_stripe_payment_intent_id ON customer_payments(stripe_payment_intent_id);
```

If we only ever need to query for `NULL` values, this index will be extremely wasteful, using a lot of memory when only a tiny portion of the records should be indexed, we can solve this by making it a partial index instead:

```sql
CREATE INDEX cus_pay_stripe_payment_intent_id
ON customer_payments(stripe_payment_intent_id)
WHERE stripe_payment_intent_id IS NULL;
```

The index will now only contain the elements with `NULL` values for `stripe_payment_intent_id`, but this is still wasteful. Rows are initially added with a `NULL` value, so an entry will be written to the index, only to be deleted when the record is later updated. This small churn can add up on a busy system.

There are alternatives, such as using a different index type, like [BRIN indexes][doc-brin-index]. This topic will be explored in another article.

## Acknowledgement

Thank you to Brian Cobb ([@bcobb](https://twitter.com/bcobb)) and Sunny Ng ([@_blahblahblah](https://twitter.com/_blahblahblah)) for reviewing an early draft of this post and providing valuable feedback.

It was mentioned above, but the ["Implementing Stripe-like Idempotency Keys in Postgres" article][idempotency-article] is an amazing resource for a deeper dive into idempotency. And Stripe!


[doc-brin-index]:https://postgresql.org/docs/13/interactive/brin.html
[idempotency-article]:https://brandur.org/idempotency-keys
[^1]:https://en.wikipedia.org/wiki/German_tank_problem
