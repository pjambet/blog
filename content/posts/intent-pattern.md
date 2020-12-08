---
title: "Intent Pattern"
date: 2020-12-08T11:39:45-05:00
lastmod: 2020-12-08T11:39:45-05:00
tags : [ "dev" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: true
---

**tl;dr;** Before creating a resouce with an upstsream service, first create an intent record, acting as an empty shell. Once the resource is created on the upstream service, finalize the intent record by updating it with a unique identifier.

https://brandur.org/idempotency-keys

## Problem

Creating resources remotely is a common operation, being in a micro-services world or not, and handling all the edge cases outside the happy path can be tricky. Network errors will happen, such as timeout issues. A common problem is the creation of a "dangling resource", a resource successfully created with an upstream service, without the initiator of the request being aware of it.

## Definition

### Nomenclature

This pattern is useful when two servers communicate with each other. The server initiating the request is referred as the "frontend" and the server receiving the request is referred to as the "backend". Note that you may or may not own the backend in this context. It may be a third party API, such as Stripe, or GitHub, or another service owned and operated by your organization.

---

Intent records are used to guarantee that no resources will created on the backend, without the frontend having a papertrail of the resource creation intent.

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

### Notes

It is a good practice to use timeouts when issuing HTTP requests, it could otherwise lead to hanging requests, causing a long wait 

## Context

We started using this pattern at Harry's back in 2016 when we were working on our first extraction of a service from our initial monolith.

Stripe, bla bla bla ...

{{< mermaid >}}
sequenceDiagram
    participant User
    participant Browser
    participant Rails App
    participant Payment Service
    User->>+Browser: Click "Place Order"
    Browser->>+Rails App: Order request
    Rails App->>+Payment Service: Charge request
    Payment Service-->>-Rails App: New charge object
    Rails App-->>-Browser: New order
    Browser-->>-User: Order placed!
{{< /mermaid >}}

## Appendix A: Cleaning up records efficiently

Use indices, Brin can be cool
