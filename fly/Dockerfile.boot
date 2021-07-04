FROM local_discourse/fly_web_only

CMD /sbin/boot

COPY fly/preboot fly/preboot
COPY fly/postboot fly/postboot

WORKDIR /var/www/discourse