## Discourse on Fly

This repo is a clone of [Discourse docker](https://github.com/discourse/discourse_docker), with some scripts and files added for building and deploying on Fly.io with external Postgresql and Redis clusters.

This is a fairly painful process today. Discourse does not support this kind of installation, and have clearly stated that it's against [their interests as a hosting company](https://meta.discourse.org/t/can-discourse-ship-frequent-docker-images-that-do-not-need-to-be-bootstrapped/33205/19) to do so. :shrug:

Our approach, then, should try to reuse as much of the core behavior of Discourse. 

### Building a deployable image

Discourse provides a script and some sample templates for bootstrapping a Docker image. In essence, it will:

* Grab the [discourse base image](https://hub.docker.com/r/discourse/base)
* Apply some configuration to its contents
* Write out a new deployable Docker image

Many initial settings may be set directly on the container environment. However, a few can't. Those that are required are uncommented in `config/fly_web_only.yml`, and should be filled in before building.

To run the build, just run `./fly-build` on any machine with Docker.

A successful build should result in a new Docker image named `fly-discourse`.

### Deployment

You should have setup a Redis and Postgresql cluster in Fly before deploying.

See the example `fly.toml`. All environment variables - and secrets mentioned therein - should be set before deployment. The first deployment will setup the database schema and initial site settings.

After the first `fly deploy`, the service should be running.

Now we need to compile the Rails assets. This step should eventually be pulled into pre-deployment bootstrap phase. But, since asset compilation currently requires access to the database. To do this, we will run:

`fly ssh console -C "./fly/postboot`


## Notes and Todos

`DISCOURSE_HOSTNAME` must be set in the `config/fly_web_only.yml` as it is used during the initial database settings bootstrap. It's also the value that gets used in the From address in initial admin verification emails.

Apparently an SMTP gateway must be setup, since you need to get emails to be able to setup the forum.

Next steps:

* Figure out how to bootstrap the deployable container with compiled assets in the production environment
* Look into issues with mixed content delivery
* Setup dedicated volume mounts, if necessary
* Test features to ensure correct behavior
* Create a plugin to inject [fly-ruby](https://github.com/superfly/fly-ruby) for multiregion postgres support