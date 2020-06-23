---
title: "Direct Upload to S3 with CORS"
date: 2012-09-21T15:07:53-04:00
lastmod: 2020-06-17T15:07:53-04:00
tags : [ "dev", "aws", "rails", "s3", "javascript" ]
categories : [ "dev" ]
description: "How to upload file directly to amazon s3 using jQuery"
layout: post
highlight: false
draft: false
---

##### Originally published at [https://pjambet.github.io/](https://pjambet.github.io/blog/direct-upload-to-s3)

## EDIT

Everything detailed in this article has been wrapped up in [this gem](https://github.com/waynehoover/s3_direct_upload), you should give it a look !

Anyway, I still advise you to read this article as it will probably help you how everything works !

## Preface

Since beginning of september, Amazon added [CORS](http://www.w3.org/TR/cors/) support to S3. As this is quite recent,
there are not yet a lot of documentation and tutorials about how to set eveything up and running for your app.

Furthermore, [this jQuery plugin](http://blueimp.github.com/jQuery-File-Upload/) is awesome, mainly for the progress bar
handling, but sadly the example [in the wiki](https://github.com/blueimp/jQuery-File-Upload/wiki/Upload-directly-to-S3)
is obsolete.

If somehow you're working with heroku you might have already faced the 30s limit on each requests. There are some
alternatives, such as the extension of the great [carrier wave gem](https://github.com/jnicklas/carrierwave),
[carrierwave direct](https://github.com/dwilkie/carrierwave_direct). I gave it a quick look, but I found it quite
crappy, as it forces you to change your carrier wave settings (removing the store_dir method, really ?) and it only
works for a single file. So I thought it would be better to handle upload manually for big files, and rely on vanilla
carrier_wave for my other small uploads.

I found other interesting examples but they all lacked important things, and none of them worked out of the box, hence
this short guide. This tutorial is inspired by [that
post](http://highgroove.com/articles/2012/09/11/upload-directly-to-Amazon-s3-with-support-for-cors.html) and [that
one](http://www.ioncannon.net/programming/1539/direct-browser-uploading-amazon-s3-cors-fileapi-xhr2-and-signed-puts/).

## Setup your bucket

First you'll need to setup your bucket to enable CORS under certain conditions.

```xml
<CORSConfiguration>
    <CORSRule>
    <AllowedOrigin>*</AllowedOrigin>
    <AllowedMethod>GET</AllowedMethod>
    <AllowedMethod>POST</AllowedMethod>
    <AllowedMethod>PUT</AllowedMethod>
    <AllowedHeader>*</AllowedHeader>
    </CORSRule>
</CORSConfiguration>
```

Of course those settings are only for development purpose, you'll probably want to restrict the Allowed Origin rule to
your domain only. [Documentation](http://docs.amazonwebservices.com/AmazonS3/latest/dev/cors.html) about those settings
is quite good.

## Setup your server

In order to send your files to s3, you have to include a set of options as described [in the official doc
here](http://aws.amazon.com/articles/1434).

One solution would be to directly write the content of all those variables in the form, so it's ready to be submitted,
but I believe that most of those value should not be written in the DOM. So we'll create a new route we'll use to fetch
those data.

This example is written with Rails, but writing the same for another framework should be really simple

```ruby
MyApp::Application.routes.draw do
    resources :signed_url, only: :index
end
```

Now that we have our new route, let's create the controller which will send back our data to the s3 form

```ruby
class SignedUrlsController < ApplicationController
    def index
    render json: {
        policy: s3_upload_policy_document,
        signature: s3_upload_signature,
        key: "uploads/#{SecureRandom.uuid}/#{params[:doc][:title]}",
        success_action_redirect: "/"
    }
    end

    private

    # generate the policy document that amazon is expecting.
    def s3_upload_policy_document
    Base64.encode64(
        {
        expiration: 30.minutes.from_now.utc.strftime('%Y-%m-%dT%H:%M:%S.000Z'),
        conditions: [
            { bucket: ENV['S3_BUCKET'] },
            { acl: 'public-read' },
            ["starts-with", "$key", "uploads/"],
            { success_action_status: '201' }
        ]
        }.to_json
    ).gsub(/\n|\r/, '')
    end

    # sign our request by Base64 encoding the policy document.
    def s3_upload_signature
    Base64.encode64(
        OpenSSL::HMAC.digest(
        OpenSSL::Digest::Digest.new('sha1'),
        ENV['AWS_SECRET_KEY_ID'],
        s3_upload_policy_document
        )
    ).gsub(/\n/, '')
    end
end
```

The policy and signature method are stolen from the linked blog posts above with one exception, I had to include the
"starts-width" constraint, otherwise s3 was yelling 403 to me.
Everything else is quite straight forward, there's just a small detail to consider if you set the acl to 'private', but
more on that later.

One last detail, the key value is actually the path of your file on your bucket, so set it to whatever you want but be
sure it matches the constraint you set in the policy. Here we're using `params[:doc][:file]` to read the name of the
file we're about to upload. We'll see more about that when setting the javascript.

That's basically everything we have to do on the server side

## Add the jQueryFileUpload files

Next you'll have to add the [jQueryFileUpload](http://blueimp.github.com/jQuery-File-Upload/) files. The plugins ships
with a lof of files, but I found most of them useless, so here is the list

- `vendor/jquery.ui.widget`
- `jquery.fileupload`

## Setup the javascript client side

Now let's setup jQueryFileUpload to send the correct data to s3

Based on what we did on the server, the workflow will be composed of 2 requests, first, it's going to fetch the needed data from our server, then send everything to s3.

Here is the form I'm using, the order of parameter is important.

```haml
%form(action="https://#{ENV['S3_BUCKET']}.s3.amazonaws.com" method="post" enctype="multipart/form-data" class='direct-upload')
    %input{type: :hidden, name: :key}
    %input{type: :hidden, name: "AWSAccessKeyId", value: ENV['AWS_ACCESS_KEY_ID']}
    %input{type: :hidden, name: :acl, value: 'public-read'}
    %input{type: :hidden, name: :policy}
    %input{type: :hidden, name: :signature}
    %input{type: :hidden, name: :success_action_status, value: "201"}

    %input{type: :file, name: :file }
    - # You can recognize some bootstrap markup here :)
    .progress.progress-striped.active
        .bar
```

```js
$(function() {

  $('.direct-upload').each(function() {

    var form = $(this)

    $(this).fileupload({
      url: form.attr('action'),
      type: 'POST',
      autoUpload: true,
      dataType: 'xml', // This is really important as s3 gives us back the url of the file in a XML document
      add: function (event, data) {
        $.ajax({
          url: "/signed_urls",
          type: 'GET',
          dataType: 'json',
          data: {doc: {title: data.files[0].name}}, // send the file name to the server so it can generate the key param
          async: false,
          success: function(data) {
            // Now that we have our data, we update the form so it contains all
            // the needed data to sign the request
            form.find('input[name=key]').val(data.key)
            form.find('input[name=policy]').val(data.policy)
            form.find('input[name=signature]').val(data.signature)
          }
        })
        data.submit();
      },
      send: function(e, data) {
        $('.progress').fadeIn();
      },
      progress: function(e, data){
        // This is what makes everything really cool, thanks to that callback
        // you can now update the progress bar based on the upload progress
        var percent = Math.round((e.loaded / e.total) * 100)
        $('.bar').css('width', percent + '%')
      },
      fail: function(e, data) {
        console.log('fail')
      },
      success: function(data) {
        // Here we get the file url on s3 in an xml doc
        var url = $(data).find('Location').text()

        $('#real_file_url').val(url) // Update the real input in the other form
      },
      done: function (event, data) {
        $('.progress').fadeOut(300, function() {
          $('.bar').css('width', 0)
        })
      },
    })
  })
})
```

So quick explanation about what's going on here :

The `add` callback allows us to fetch the missing data before the upload. Once we have the data, we simply insert them
in the form

The `send` and `done` callbacks are only used for UX purpose, they show and hide the progress bar when needed. The real
magic is the `progress` callback as it gives you the current progress of the upload in the event argument.

In my example, this form sits next to a 'real' rails form which is used to save an object which has amongst its
attributes a file_url, linked to the "big file" we just uploaded. So once the upload is done I fill the 'real' field so
my object is correctly created with the good url without having to handle extra things. After submitting the real form
my object is saved with the URL of the file uploaded on S3.

If you're uploading public files, you're good to go, everything's perfect. But if you're uploading private file (this is
set with the acl params), you still have a last thing to handle.

Indeed the url itself is not enough, if you try accessing it, you'll face some ugly xml [like
that](https://s3-eu-west-1.amazonaws.com/lpdc/glyphicons_003_user.png).
The solution I used was to use the [aws gem](http://amazon.rubyforge.org/) which provides a great method :
[AWS::S3Object#url_for](http://amazon.rubyforge.org/doc/classes/AWS/S3/S3Object.html). With that method, you can get an
authorized url for the desired duration with your bucket name and the key (the path of your file in the bucket) of your
file

So my custom url accessor looked something like this :

```ruby
def url
    parent_url = super
    # If the url is nil, there's no need to look in the bucket for it
    return nil if parent_url.nil?

    # This will give you the last part of the URL, the 'key' params you need
    # but it's URL encoded, so you'll need to decode it
    object_key = parent_url.split(/\//).last
    AWS::S3::S3Object.url_for(
    CGI::unescape(object_key),
    ENV['S3_BUCKET'],
    use_ssl: true)
end
```

This involves some weird handling with the `CGI::unescape`, and there's probably a better way to achieve this, but this is one way to do it, and it works fine.

## Live example

I'll set up a live example running on heroku, on which you'll be able to upload files in more than 30s coming soon

### Finally !

The demo if finally here : http://direct-upload.herokuapp.com and code source can be found here :  https://github.com/pjambet/direct-upload

## EDIT

I changed every access to AWS variables (BUCKET, SECRET_KEY and ACCESS_KEY) by using environment variables.
By doing so you don't have to put the variables directly in your files, but you just have to set correctly the variables :

```ruby
export S3_BUCKET=<YOUR BUCKET>
export AWS_ACCESS_KEY_ID=<YOUR KEY>
export AWS_SECRET_KEY_ID=<YOUR SECRET KEY>
```

When deploying on heroku you just have to set the variables with

```
heroku config:add AWS_ACCESS_KEY_ID=<YOUR KEY> --app <YOUR APP>
...
```
