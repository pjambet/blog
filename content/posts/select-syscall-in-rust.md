---
title: "Select Syscall in Rust"
date: 2020-11-22T17:29:10-05:00
lastmod: 2020-11-22T17:29:10-05:00
tags : [ "dev", "rust" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: false
---

## Disclaimer

1. I am _not_ a Rust expert, I am just getting started, so please take everything you read here with a grain of salt.
2. You probably don't want to use any of this in production code. There are libraries written by _actual_ Rust developers providing similar features, in a way that will most certainly be more efficient and more reusable, such as [Tokio][tokio] and [nix][nix]. Additionally, `select` is rarely use these days, [`kqueue`][kqueue] and [`epoll`][epoll] tend to be preferred.

Good? Let's get to it now.

# The `select` syscall

The [`select` syscall][wikipedia-select] is really useful to write systems using an [event loop][wikipedia-event-loop]. A good example is a database running as a TCP server. A database would want to maintain client connections open, and respond to them when then send queries to the server, and `select` can help with that.

`select` works by accepting four arguments, a list of [file descriptors][wikipedia-fd] that are ready to be read from, a list of file descriptors that are ready to be written to, a list of file descriptors that have an exceptional condition pending and finally, an optional timeout. We give these file descriptors to `select` and it'll return as soon as one or more file descriptors are ready.

A file descriptor, often abbreviated to `fd` is the identifier of a file or other input/output resource, such as a pipe or network socket. It is based on the idea that, in UNIX, [everything is a file][wikipedia-everything-is-a-file]. In practical terms file descriptors are non-negative integers.

In the database example, we would give file descriptors for the connected clients, based on the sockets we created when they connected, and `select` would wait until one of them is ready. Essentially, as soon as the TCP server receives something from one of the clients, the server would be able to respond.

If you're looking for real world example, Redis does exactly this if configured to use `select`, in [`ae_select.c`](https://github.com/redis/redis/blob/unstable/src/ae_select.c). As noted earlier, it prefers other polling mechanisms, such as `kqueue` and `epoll` and `select` is only a last resort option.

[`pselect`][pselect-man-page] is very similar to `select`, with the difference that it accepts a [`timespec`][libc-src-timespec] instead of a [`timeval`][libc-src-timeval] and accepts a mask argument to have better control over signal handling.

## Using the `libc` crate

Rust itself does not provide a way to call `select` or `pselect` directly from the standard library, but the Rust developers created the `libc` library that provides bindings to these, and more.

The nix crate mentioned at the beginning of this post uses the `libc` crate under the hood. This is how it provides bindings to the `select` syscall: https://github.com/nix-rust/nix/blob/master/src/sys/select.rs

## Show me some code

The following is an example of using `select` to be notified when a TCP server sent _something_ back to a connected client. For this example I was using a TCP server running in Ruby with the following code running in `irb`:

``` ruby
irb(main):001:0> require 'socket'
=> true
irb(main):002:0> server = TCPServer.new 'localhost', 2000
irb(main):003:0> client1 = server.accept; client2 = server.accept; client3 = server.accept

```

The last line does not return until three clients connect, which is what the following Rust code does:

``` rust
extern crate libc;

use std::net::TcpStream;
use std::os::unix::io::{AsRawFd, RawFd};
use std::{io, mem, ptr, time};

pub struct FdSet(libc::fd_set);

impl FdSet {
    pub fn new() -> FdSet {
        unsafe {
            let mut raw_fd_set = mem::MaybeUninit::<libc::fd_set>::uninit().assume_init();
            libc::FD_ZERO(&mut raw_fd_set);
            FdSet(raw_fd_set)
        }
    }
    pub fn clear(&mut self, fd: RawFd) {
        unsafe { libc::FD_CLR(fd, &mut self.0) }
    }
    pub fn set(&mut self, fd: RawFd) {
        unsafe { libc::FD_SET(fd, &mut self.0) }
    }
    pub fn is_set(&mut self, fd: RawFd) -> bool {
        unsafe { libc::FD_ISSET(fd, &mut self.0) }
    }
}

fn to_fdset_ptr(opt: Option<&mut FdSet>) -> *mut libc::fd_set {
    match opt {
        None => ptr::null_mut(),
        Some(&mut FdSet(ref mut raw_fd_set)) => raw_fd_set,
    }
}
fn to_ptr<T>(opt: Option<&T>) -> *const T {
    match opt {
        None => ptr::null::<T>(),
        Some(p) => p,
    }
}

pub fn select(
    nfds: libc::c_int,
    readfds: Option<&mut FdSet>,
    writefds: Option<&mut FdSet>,
    errorfds: Option<&mut FdSet>,
    timeout: Option<&libc::timeval>,
) -> io::Result<usize> {
    match unsafe {
        libc::select(
            nfds,
            to_fdset_ptr(readfds),
            to_fdset_ptr(writefds),
            to_fdset_ptr(errorfds),
            to_ptr::<libc::timeval>(timeout) as *mut libc::timeval,
        )
    } {
        -1 => Err(io::Error::last_os_error()),
        res => Ok(res as usize),
    }
}

pub fn make_timeval(duration: time::Duration) -> libc::timeval {
    libc::timeval {
        tv_sec: duration.as_secs() as i64,
        tv_usec: duration.subsec_micros() as i32,
    }
}

pub fn connect_to_localhost_2000() -> TcpStream {
    TcpStream::connect("localhost:2000").expect("Failed to connect to localhost 2000")
}

fn main() {
    let mut fd_set = FdSet::new();

    let stream1 = connect_to_localhost_2000();
    let raw_fd1 = stream1.as_raw_fd();

    let stream2 = connect_to_localhost_2000();
    let raw_fd2 = stream2.as_raw_fd();

    let stream3 = connect_to_localhost_2000();
    let raw_fd3 = stream3.as_raw_fd();

    // let raw_fd2 = connect_to_localhost_2000().as_raw_fd(); DOES NOT WORK

	let max_fd = raw_fd1.max(raw_fd2.max(raw_fd3));

    println!("Socket 1: {}", raw_fd1);
    println!("Socket 2: {}", raw_fd2);
    println!("Socket 3: {}", raw_fd3);

    fd_set.set(raw_fd1);
    fd_set.set(raw_fd2);
    fd_set.set(raw_fd3);

    match select(
        max_fd + 1,
        Some(&mut fd_set),                               // read
        None,                                            // write
        None,                                            // error
        Some(&make_timeval(time::Duration::new(10, 0))), // timeout
    ) {
        Ok(res) => {
            println!("select result: {}", res);

            let range = std::ops::Range {
                start: 0,
                end: max_fd + 1,
            };
            for i in range {
                if (fd_set).is_set(i) {
                    println!("Socket {} received something!", i);
                }
            }
        }
        Err(err) => {
            println!("Failed to select: {:?}", err);
        }
    }
}
```

Let's break it down, first we declare a `Rust` struct, `FdSet`, which wraps `libc::fd_set`. An `fd_set` is not the same on every platform, but based on the [`libc` source](https://github.com/rust-lang/libc/blob/master/src/unix/bsd/mod.rs#L57-L64), we can see that it is defined as an array of integers:

``` rust
pub struct fd_set {
	#[cfg(all(target_pointer_width = "64",
			  any(target_os = "freebsd", target_os = "dragonfly")))]
	fds_bits: [i64; FD_SETSIZE / 64],
	#[cfg(not(all(target_pointer_width = "64",
				  any(target_os = "freebsd", target_os = "dragonfly"))))]
	fds_bits: [i32; FD_SETSIZE / 32],
}
```


We can use the macros that come with `select` to interact with it without having to worry too much about the underlying implementation details, `FD_ZERO`, `FD_CLR`, `FD_SET` and `FD_ISSET`

The next block in `impl FdSet` provides Rust functions for the `FdSet` type to use these macros. In the `new` function we create a new instance of `FdSet`, while using `MaybeUninit` to prevent Rust to do what it does for _regular_ variables and that it essentially should trust us here, we know what we're doing.

`FD_ZERO` is used to make sure that the integers that were allocated are in a clean state, technically speaking the OS does not have to clear the bits that were allocated, so we do it, just in case.

The next two functions, `to_fdset_ptr` and `to_ptr` are helper functions to convert some Rust-y things such as `Option` values into C things, like a `null` pointer.

Next is the actual binding to `libc::select`, where we accept all the values we want to pass, as idiomatic Rust values, that is some `Option` wrapped `FdSet` values, instead of explicit `null` values.

The return value is also translated from the C tradition of returning `-1` if something went wrong to a `Result` type, which allows us to use pattern matching when dealing with the return value of select.

The timeout passed to `select` is a `timeval`, which is a bit verbose to write, so we also create a helper function to instantiate one based on a Rust `Duration` value.

You can read more about the details of select in the man page, with `man 2 select`, which is also available online for [linux][man-select-linux], and [macOS][man-select-linux].

We will connect three clients to the server so we created a small helper function to do that for us, `connect_to_localhost_2000`. We use the [`as_raw_fd` function](http://doc.rust-lang.org/1.47.0/std/net/struct.TcpStream.html#method.as_raw_fd), from the [`TcpStream` type](http://doc.rust-lang.org/1.47.0/std/net/struct.TcpStream.html), which returns a [`RawFd`](http://doc.rust-lang.org/1.47.0/std/os/unix/io/type.RawFd.html), which an alias for an integer, specifically `c_int`, which itself is an alias for `i32`, a 32-bit signed integer, for _most_ platforms.

And now, the `main` method, we start by creating an `FdSet`, this will be the one and only `fd_set` we'll give to `select` since we don't care about writable sockets neither do we care about the ones with exception pending in this example.

We then connect three sockets using `connect_to_localhost_2000`.

---

Note that while it might look tempting to write the following if you come from a different language:

``` rust
let raw_fd = connect_to_localhost_2000().as_raw_fd(); DOES NOT WORK
```

This will _not_ work due to how Rust automatically releases variables that are not needed anymore. In this case the `TcpStream` variable returned by `connect_to_localhost_2000` is used to call `as_raw_fd()` but is not needed anymore after that, and Rust will release it. The impact is that Rust knows that the release process for this variable involves closing the socket, which we absolutely do not want here. We need the socket to stay open until the end of the `main` function, so that `select` can use it. One way of doing this is to explicitly create a variable for the stream, which will force it to stay in scope for the rest of the function.

---

Back to `main`, we create the `max_fd` variable and set it to the max value of the three raw sockets. This is necessary because the first argument to `select` _must_ be the the value of the file descriptors given, plus one. Most of the time the file descriptors are incremented, and while running this, my machine was consistently creating these three sockets as `5`, `6` and `7`, but this is not something that we should rely on, and explicitly grabbing the max value is more reliable.

We then need to prepare the `fd_set` variable for `select`, and this is what `FD_SET` is for, which we use through the `fd_set` function. It will take care of setting the correct bits inside the `fd_set` array to store the information of the file descriptors.

We can then call `select`, with `max_fd + 1`, as mentioned above. If we have passed min fd value plus one instead, only that socket would have been monitored. If you're coming from a higher level language, such as Ruby, this might seem odd, but is essentially an "optimized" (one might see it as convoluted) way of passing an array of integers to `select`. `select` will know it will not have to look for file descriptors with a value greater than this value.

We also pass a timeout of ten seconds, which is an arbitrary value, and `None` values for the other arguments.

The function returns a `Result`, which will be an `Err` if something went wrong, for instance if we had used the inline version mentioned above, we would have received the following error due to the socket being closed: `Failed to select: Os { code: 9, kind: Other, message: "Bad file descriptor" }`

On the other hand if the result is successful, we want to know which sockets can be read from. To do that, we need to iterate through the range of all possible file descriptors that could have been described by `fd_set`, which is all the number between `0` and `max_fd`, inclusive.

For each of the file descriptors, we use the `FD_ISSET` macro, through the `is_set` function to ask if this file descriptor is set in `fd_set`, it will only be set if the file descriptor can be read from.

If we had been interested in which sockets could be written to, we would have created a different `fd_set` and use the same approach on each `fd_set`.

In other words, `select` modifies the `fd_set` you give it and sets the internal bits for the file descriptors that are ready, it's then up to you to look at the content of `fd_set` and detect which file descriptors are ready. It is also up to you to keep track of which `fd_set` was given to be notified for readability and which one was given for writability.

One metaphor to explain this process is that we give `select` a piece of paper with a list of file descritor ids, each followed by an empty checkbox, and `select` gives it back to us with the checkbox checked for all the ones that are ready.

If you're testing this locally, you'll only have ten seconds to do something from `irb`, feel free to change the value, or pass `None` if you want an infinite timeout.

When writing to a single client in Ruby, with `client2.write '123'`, I got the following output for my Rust program:

```
Socket 1: 5
Socket 2: 6
Socket 3: 7
select result: 1
Socket 6 received something!
```

And we can see that if multiple sockets are ready, they're all detected, which we can test by writing to two clients with `client2.write '123'; client1.write '456'`, which gives us the following output:

```
Socket 1: 5
Socket 2: 6
Socket 3: 7
select result: 2
Socket 5 received something!
Socket 6 received something!
```

It works!

## Conclusion

At the risk of repeating myself, the purpose of all this is _only_ to learn more about Rust and `select`, if you're writing a _real_ application, look into nix and Tokio instead.

Most of the code was adapted from this [Gist](https://gist.github.com/AGWA/b0931a912a8b22b2d6178a3155e171f3) I foound [on Reddit](https://www.reddit.com/r/rust/comments/65kflg/does_rust_have_native_epoll_support/).

You can find [the code on GitHub](https://github.com/pjambet/rust-and-select)

---

Liked this, you _might_ like my [free online book](https://redis.pjam.me/) about Rebuilding Redis, in Ruby.

---

Appendix: Same example, but with `pselect`:

``` rust
extern crate libc;

use std::net::TcpStream;
use std::os::unix::io::AsRawFd;
use std::os::unix::io::RawFd;
use std::{io, mem, ptr, time};

pub struct FdSet(libc::fd_set);

impl FdSet {
    pub fn new() -> FdSet {
        unsafe {
            let mut raw_fd_set = mem::MaybeUninit::<libc::fd_set>::uninit().assume_init();
            libc::FD_ZERO(&mut raw_fd_set);
            FdSet(raw_fd_set)
        }
    }
    pub fn clear(&mut self, fd: RawFd) {
        unsafe { libc::FD_CLR(fd, &mut self.0) }
    }
    pub fn set(&mut self, fd: RawFd) {
        unsafe { libc::FD_SET(fd, &mut self.0) }
    }
    pub fn is_set(&mut self, fd: RawFd) -> bool {
        unsafe { libc::FD_ISSET(fd, &mut self.0) }
    }
}

fn to_fdset_ptr(opt: Option<&mut FdSet>) -> *mut libc::fd_set {
    match opt {
        None => ptr::null_mut(),
        Some(&mut FdSet(ref mut raw_fd_set)) => raw_fd_set,
    }
}
fn to_ptr<T>(opt: Option<&T>) -> *const T {
    match opt {
        None => ptr::null::<T>(),
        Some(p) => p,
    }
}

pub fn pselect(
    nfds: libc::c_int,
    readfds: Option<&mut FdSet>,
    writefds: Option<&mut FdSet>,
    errorfds: Option<&mut FdSet>,
    timeout: Option<&libc::timespec>,
    sigmask: Option<&libc::sigset_t>,
) -> io::Result<usize> {
    match unsafe {
        libc::pselect(
            nfds,
            to_fdset_ptr(readfds),
            to_fdset_ptr(writefds),
            to_fdset_ptr(errorfds),
            to_ptr(timeout),
            to_ptr(sigmask),
        )
    } {
        -1 => Err(io::Error::last_os_error()),
        res => Ok(res as usize),
    }
}

pub fn make_timespec(duration: time::Duration) -> libc::timespec {
    libc::timespec {
        tv_sec: duration.as_secs() as i64,
        tv_nsec: duration.subsec_nanos() as i64,
    }
}

pub fn connect_to_localhost_2000() -> TcpStream {
    TcpStream::connect("localhost:2000").expect("Failed to connect to localhost 2000")
}

fn main() {
    let ten_seconds = time::Duration::new(10, 0);
    let mut fd_set = FdSet::new();

    let stream1 = connect_to_localhost_2000();
    let raw_fd1 = stream1.as_raw_fd();

    let stream2 = connect_to_localhost_2000();
    let raw_fd2 = stream2.as_raw_fd();

    let stream3 = connect_to_localhost_2000();
    let raw_fd3 = stream3.as_raw_fd();

    let max_fd = raw_fd1.max(raw_fd2.max(raw_fd3));

    println!("Socket 1: {}", raw_fd1);
    println!("Socket 2: {}", raw_fd2);
    println!("Socket 3: {}", raw_fd3);

    fd_set.set(raw_fd1);
    fd_set.set(raw_fd2);
    fd_set.set(raw_fd3);

    match pselect(
        max_fd + 1,
        Some(&mut fd_set),                 // read
        None,                              // write
        None,                              // error
        Some(&make_timespec(ten_seconds)), // timeout
        None,                              // mask
    ) {
        Ok(res) => {
            println!("select result: {}", res);

            let range = std::ops::Range {
                start: 0,
                end: max_fd + 1,
            };
            for i in range {
                if (fd_set).is_set(i) {
                    println!("Socket {} received something!", i);
                }
            }
        }
        Err(err) => {
            println!("Failed to select: {:?}", err);
        }
    }
}
```

[man-select-macos]:https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/select.2.html
[man-select-linux]:https://man7.org/linux/man-pages/man2/select.2.html
[kqueue]:https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/kqueue.2.html
[epoll]:https://linux.die.net/man/4/epoll
[wikipedia-event-loop]:https://en.wikipedia.org/wiki/Event_loop
[wikipedia-select]:https://en.wikipedia.org/wiki/Select_(Unix)
[wikipedia-fd]:https://en.wikipedia.org/wiki/File_descriptor
[wikipedia-everything-is-a-file]:https://en.wikipedia.org/wiki/Everything_is_a_file
[pselect-man-page]:https://linux.die.net/man/2/pselect
[libc-src-timespec]:https://github.com/rust-lang/libc/blob/0.2.80/src/unix/mod.rs#L63-L69
[libc-src-timeval]:https://github.com/rust-lang/libc/blob/0.2.80/src/unix/mod.rs#L56-L59
[tokio]:https://github.com/tokio-rs/tokio
[nix]:https://github.com/nix-rust/nix
