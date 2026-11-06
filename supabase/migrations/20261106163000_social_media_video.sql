-- Extend the private social bucket to support post photos and MP4 video.
update storage.buckets
set file_size_limit = 52428800,
    allowed_mime_types = array['image/jpeg','image/png','image/webp','video/mp4']
where id = 'social-media';
