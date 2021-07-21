This repo is a clone of [Discourse docker](https://github.com/discourse/discourse_docker), with some scripts and files added for building and deploying on Fly.io with external Postgresql and Redis clusters.

## Running Discourse on Fly.io

Discourse is a curious player in the open source application space. True, at its core it's a 10+ year old, battle-tested Rails app. But its only supported container-based deployment strategy bundles Sidekiq, Nginx, Redis, Postgresql, and even a [custom websocket-based message bus](https://github.com/discourse/message_bus) into a single image, designed to run on a single server.

While this approach is dead simple for simple installations, it's a challenge to extract these services and run Discourse as a [12-factor](https://12factor.net/) web application. You might want to do this to scale a large installation, get peace of mind from managed database services, or simply because you think it's the right way to deploy these days.

On Fly, another reason to do this is to take advantage of globally distributed [Postgresql](https://fly.io/blog/globally-distributed-postgres/) and [Redis](https://fly.io/blog/last-mile-redis/) instances to improve perceived application performance. We cover the experiments we ran to attempt this [later in this document](#multiple-region-deployment).

In this repo, we try to keep changes to the supported build minimal so we may continue to merge upstream changes.

### Building a deployable image

If you just want to try a deployment, [skip here for single region deployment instructions](#single-region-deployment).

#### Changes for deployment on Fly

Discourse provides their [launcher script](https://github.com/discourse/discourse_docker/blob/master/launcher) and some [sample configuration templates](https://github.com/discourse/discourse_docker/blob/master/samples) for generating a deployable Docker image. 

Roughly, the script will:

* Grab the [discourse base image](https://hub.docker.com/r/discourse/base)
* Clone the current discourse repository in a temporary container
* Apply some configuration to its contents
* Validate user-supplied configuration options
* Install Ruby gems and other dependencies
* Save a new, deployable Docker image

We stick to this approach, but with a few changes:

We allow fetching our own fork of Discourse, for testing experimental changes like region-local datbaase support.

We inject Ruby gems we want installed in the final image, such as [fly-ruby](https://github.com/superfly/fly-ruby) or [scout_apm](https://github.com/scoutapp/scout_apm_ruby/).

We skip the configuration validation phase, since we will be applying configuration options through the container environment, converted into application config by Discourse's [EnvProvider](https://github.com/discourse/discourse/blob/main/app/models/global_setting.rb#L323). This could be improved so validation takes place, and then applies the necessary configuration using `flyctl`.

Because Rails asset precompilation needs access to the production databases, we split deployment into a few steps.

1. We run `launcher bootstrap web` inside an ephemeral Docker build. This allows any machine to build a new base image, sans precompiled assets.

2. This base image is pushed to Fly.

3. Finally, we build upon this image on a Fly remote builder. The builder VM has access to the production environment, os it may compile Rails assets. Currently, this requires passing `DISCOURSE_DB_PASSWORD` as a build argument.

### Single region deployment

Pick a region and ensure you have setup a [Redis](https://github.com/fly-apps/redis-geo-cache) and [Postgresql](https://github.com/fly-apps/postgres-ha) instance there. Here we've chosen `iad`.

See `fly.toml.example` for available configuration options, and copy it to `fly.toml`. Create a Fly application and update `app` accordingly. All environment variables in `fly.toml` - and secrets mentioned therein - should be set before deployment.

Setup an SMTP gateway somewhere. This appears to be required, since email verification is required to setup Discourse. https://www.smtp2go.com appears to allow free trials. https://www.ohmysmtp.com is a good paid service.

Run `DISCOURSE_DB_PASSWORD=your_pg_password ./fly-build` on any machine with Docker.

A successful build pushes the `fly-discourse` image to Fly's internal Docker repository. The actual deployment runs through your Fly remote builder.

Finally: visit your app and setup Discourse.

### Multiple region deployment

Our goal here is to scale Discourse to run in multiple regions, without losing performance. Ideally, we can improve it!

Reads from Postgresql and Redis can use regional replicas, and caches (redis, nginx, static files) could be colocated with applications, closer to their targets. The [fly-ruby](https://github.com/superfly/fly-ruby) gem can help us get there.

Alas, scaling Discourse this way is tricky! Almost every request performs writes to both Postgresql and Redis. Here, we'll cover our ongoing attempts to make this work in our [fork of Discourse](https://github.com/souped-up/discourse).

First, here's some unscientific but revealing numbers from hitting the app root from around the world:

```
$ fly curl https://mr-discourse.souped-up.dev

Region	Status	DNS	Connect	TLS	TTFB	Total
atl	200	57.8ms	58.5ms	167.8ms	315.1ms	317.8ms
iad	200	73.7ms	73.9ms	169.4ms	362.3ms	363.2ms
ewr	200	85.3ms	86.4ms	201.9ms	269.5ms	270.5ms
yyz	200	170.7ms	171.4ms	390.7ms	342.1ms	344.6ms
ord	200	140.8ms	141.5ms	324.7ms	415.8ms	416.9ms
dfw	200	229.5ms	230.4ms	512.1ms	355.4ms	357.6ms
lax	200	154.3ms	154.8ms	353.3ms	313.2ms	314.4ms
sjc	200	209ms	209.7ms	440.3ms	375.7ms	376.6ms
sea	200	311.1ms	311.5ms	653.9ms	513.5ms	515.7ms
nrt	200	140.9ms	141.5ms	329.6ms	524.6ms	526.8ms
syd	200	443.8ms	444.9ms	942.3ms	991.6ms	994.5ms
```

Our goal here is to improve the TTFB (Time-To-First-Byte) values in regions other than `iad`.

#### Regional Postgres

First, we need to scale up our Postgresql cluster. This is as simple as adding a volume in another region and scaling up.

```
fly volumes create discourse_data --region cdg -a discourse-db
fly scale count 2 -a discourse-db
```

Now we have a replica, we should add volumes in both regions to get the same regional specificity in our Rails deployment. Then, we scale up.

```
fly volumes create discourse_data --region cdg -a discourse
fly scale count 2 -a discourse
```

Discourse should now be running in the `cdg` region, but using a slow, cross-region connection to the primary Postgresql instance.

To get `cdg` discourse to use the replica in `cdg`, we merely need to set the `PRIMARY_REGION` in `fly.toml` to `iad` and redeploy. The [fly-ruby]() gem will pick this up and inject middleware that automatically reconnects the `cdg` deployment to `cdg` Postgres.

Here's where the fun starts!

By default, all non-idempotent HTTP methods will be replayed in the primary region. GET requests that attempt to write to the database will also be replayed. We can inspect the `Fly-Region` header - appended by `fly-ruby` - to discover which region handled a request. So far so good.

Alas, Discourse is reporting that it has entered *read only mode*. Uh oh.

Enter Logster, the built-in Discourse log drain and exception handler. We immediately find this exception being thrown over and over:

`Job exception: PG::ReadOnlySqlTransaction: ERROR: cannot execute UPDATE in a read-only transaction`

*Job* here refers not to a Sidekiq background job, but a custom class designed to process work asynchronously, in-process, on a background thread. These exceptions *not* make their way back to our middleware, causing a replay, since they run outside of thread where the request is handled.

In the exception backtrace, we discover that Discourse [tracks all requests in database and Redis via a middleware]():

```
/var/www/discourse/app/models/concerns/cached_counting.rb:37:in `perform_increment!'
/var/www/discourse/app/models/application_request.rb:28:in `increment!'
/var/www/discourse/lib/middleware/request_tracker.rb:70:in `log_request'
```

We also find that Discourse prematurely rescues the exact error we're trapping in `fly-ruby`, causing it to enter read-only mode. This exists so Discourse may failover to a replica in the case of a primary failure. This is great! But it complicates testing our setup here.

```
rescue_from PG::ReadOnlySqlTransaction do |e|
    Discourse.received_postgres_readonly!
```

We also see two more similar write exceptions: one for updating the current user's *last seen* attribute, and another for rotating their authentication token.

We have a few options here to deal with this problem. One would be to ensure that background work always use the primary database. For now, to simplify testing in our fork, we'll disable the request tracker, read-only mode support, and auth token checks.

Now we're running relatively error-free, but still not seeing the best numbers.

```
Insert numbers here from `fly curl`
```

Note: Discourse has [rack-mini-profiler]() enabled by default, clearing showing us that a lot of time is spent in Redis. So, we know we need to deal with slow Redis requests soon. Jump to the next section for that.

(Add screenshot)

#### Regional Redis

After adding some debugging statements to the convenient [DiscourseRedis]() wrapper around the Redis client, we noticed that every request performs both reads and writes, both to the Rails cache and to standard Redis. If we want to support the standard Discourse installation, we need to deal with this.

```
Insert some redis debugging output here
```

First let's try moving Rails caching to a region-local instance. In most cases, cache need not be shared across application instances.

In the default installation, the same Redis server is used for caching, sidekiq jobs, user settings, and [log storage](). Here, we'll scale our Redis cluster to `cdg`, allowing both reads of replicated data *and* writes for cache data.

`fly volume create redis_server --region cdg -a discourse-redis`

Note: previously we set the `DISCOURSE_SECRET_KEY_BASE` secret to avoid Discourse caching different, random values in each region. This causes issues like CSRF verification failures.

Now we have a replica in place, we need to tell Discourse to use it for Rails cache. This is not automated yet by `fly-ruby`, so we do some [monkeypatching]() to make it work in Discourse.

Finally, we want Discourse to read from our regional replica and write to the primary. To avoid blocking writes, we use the [Defer]() class mentioned above to move the Redis writes to a background thread. And we perform all
other Redis reads against the regional replica. This is simplified by [modifying Discourse's Redis client proxy]().

This will backfire in situations where reads are dependent on a write in the same request - but here we are!

The numbers we're getting now:

```
```

So be it.

## Notes and Todos

Things to do:

* Look into replaying unicorn stderr/out logs to the container stdout, to simplify debugging of failed or stalled deployments
* Setup dedicated volume mounts or s3 for uploads, logs, etc
* Look into issue with default avatars not loading from the shared folder (probably related to lack of accwess to volume mounts at bootstrap time) 
* Use discourse fork with multi-region Redis changes
