---
title: "Intent Pattern"
date: 2020-12-08T11:39:45-05:00
lastmod: 2020-12-08T11:39:45-05:00
tags : [ "dev", "design patterns" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: false
---

**This article is a draft**

**tl;dr;** Before creating a resource with an upstream service, first persist an intent record, acting as an empty shell for the future resource identifier. Once the resource is created on the upstream service, finalize the intent record by updating it with a unique identifier.

## Nomenclature

This pattern is useful when two services communicate with each other. The service initiating the request is referred to as the "frontend" and the service receiving the request is referred to as the "backend". Note that you may or may not own the backend in this context. It may be a third party API, such as Stripe, or GitHub, or another service owned and operated by your organization.

> Some people might be used to "frontend" being used to identify the client-side javascript part of a codebase but it in this article we are using it to differentiate two types of services.

## Problem

Creating resources remotely is a common operation, being in a micro-services world or not, and handling all the edge cases outside the happy path can be tricky. Network errors will happen, such as timeout issues. The main problem is the creation of "dangling resources", a resource successfully created on the backend, without the frontend being aware of it.

The following sequence illustrates the problem:

1. The frontend service sends an http request to the backend service to create a new resource
2. The backend service processes the request and creates the resource
3. A network error occurs and the frontend service loses the connection to the backend service
4. Depending on the nature of the network error, the backend service may or may not notice the connection error, but regardless, the frontend service is now unaware of the existence of the new resource

**The intent pattern solves this problem by always creating an intent record before initiating the creation of the resource.** 

## Sequence Diagram

{{< mermaid >}}
sequenceDiagram
    participant Frontend
    participant Backend
    Frontend->>Frontend: Persist intent record    
    Frontend->>+Backend: Create resource request
    Backend-->>-Frontend: Newly created resource
    Note right of Backend: Resources must include a unique identifier
    Frontend->>+Frontend: Update intent record with the unique identifier
{{< /mermaid >}}

## Description

Persisting an intent record before issuing an HTTP call for the creation of a resource with a backend service guarantees that the frontend service can serve as the source of truth. The following is an exhaustive list of all the possible scenarios:

- Happy path: no errors happen, the resource is created and the intent record is updated with the resource id
- Failure to create the intent record: something goes wrong when attempting to persist the intent record. No resource is ever created and an error is returned to the client
- Backend service error: the intent record is created, but something happened with the backend service. Depending on the nature of the error, the frontend might receive an error, and can update the intent record to flag it as "dead" or may not receive anything back from the backend. In this case the data should be reconciliated. More on that below. 
- Network error: the intent record is created, but due to a network error, the frontend never receives the resource object. There is now a consistency issue and the issue should ideally be reconciliated. More on that below.
- Failure to update the intent record: both the intent record and the resource were created, but something happened, preventing the update of the intent record. There is now a consistency issue and the issue should ideally be reconciliated. More on that below.

### Data reconcilation

Handling dangling records is application specific and the requirements will vary from one app to another. Regardless of such requirements, this pattern gives you a great starting point to guarantee the consistency of your system by being able to identify which records have been finalized and which ones have not.

An intent record that is never updated with a resource identifier should be considered "dead".

Applications should allow for a grace period to treat an intent record as "dead". The creation process on the backend might take a few seconds, so an intent record five second old without an identifier should probably not be considered dead. On the other hand, the same record should very likely be considered dead after a week. 

## Example: Charge a customer with Stripe

> The term "intent" gets overloaded between Stripe's PaymentIntent object and the "intent record" described in this article, but we started using this term in 2016, a few years before the introduction of the PaymentIntent APIs, back when Charges were the recommended option.

Charging a customer with Stripe, in its simplest form, requires an API call like the following:

```bash
$ curl https://api.stripe.com/v1/payment_intents \
  -u sk_XXX_YYY: \
  -d amount=2000 \
  -d currency=usd \
  -d confirm=true \
  -d "payment_method_types[]"=card \
  -d "payment_method"=pm_card_visa
```

It is a good practice to use timeouts when issuing HTTP requests, it could otherwise lead to hanging requests, causing a long wait, and while we observed a p95 around 4.5s for this particular request, it was not uncommon to see requests taking more than 20 or 25s.

We started using this pattern at Harry's back in 2016 when we were working on our first extraction of a service from our initial monolith.

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
    customer_id BIGINT REFERENCES customers(id),
    stripe_payment_intent_id TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
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
  # UPDATE customer_payments SET stripe_payment_id = ? WHERE id = ?;
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

## Conclusion

Efficiently Fetching dangling records can be problematic, especially with large tables. In the example above, the query `SELECT * FROM customer_payments WHERE stripe_payment_intent_id IS NULL AND created_at >= NOW() - INTERVAL '48 HOURS'` will become really slow as the table grows given that both columns are unindexed.

Easy enough, we can add an index to `stripe_payment_id`:

```sql
CREATE INDEX cus_pay_stripe_payment_intent_id ON customer_payments(stripe_payment_intent_id);
```

If we only ever need to query for `NULL` values, this index will be extremely wasteful, using a lot of memory when only a tiny portion of the records should be indexed, we can solve this by making it a partial index instead:

```sql
CREATE INDEX cus_pay_stripe_payment_intent_id ON customer_payments(stripe_payment_intent_id) WHERE stripe_payment_intent_id IS NULL;
```

The index will now only contain the elements with `NULL` values for `stripe_payment_intent_id`, but this is still wasteful. Rows are initially added with a `NULL` value, so an entry will be written to the index, only to be deleted when the record is later updated. This small churn can add up on a busy system.

There are alternatives, such as using a different index type, like [BRIN indexes][doc-brin-index]. This topic will be explored in a later article.

## Acknowledgement

Thank you to ... for reviewing an early draft of this post and providing valuable feedback.

This pattern was developped organically at Harry's as we were building new services, but it happens to be a subset of the idempotency pattern described in ["Implementing Stripe-like Idempotency Keys in Postgres"](https://brandur.org/idempotency-keys)


[doc-brin-index]:https://postgresql.org/docs/13/interactive/brin.html
