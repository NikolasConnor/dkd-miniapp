import unittest

from core import DKDError, _news_photo_mime


class NewsPhotoMimeTests(unittest.TestCase):
    def test_detects_jpeg_by_binary_signature(self):
        self.assertEqual(_news_photo_mime(bytes.fromhex("ffd8ff") + b"payload", "photo"), "image/jpeg")

    def test_detects_png_by_binary_signature(self):
        self.assertEqual(
            _news_photo_mime(bytes.fromhex("89504e470d0a1a0a") + b"payload", "photo"),
            "image/png",
        )

    def test_rejects_unknown_image_signature(self):
        with self.assertRaises(DKDError) as ctx:
            _news_photo_mime(b"not-an-image", "photo")
        self.assertEqual(ctx.exception.status, 422)

    def test_non_photo_type_does_not_claim_image_mime(self):
        self.assertEqual(_news_photo_mime(b"video", "video"), "application/octet-stream")

    def test_non_photo_media_uses_binary_default(self):
        self.assertEqual(_news_photo_mime(b"video", None), "application/octet-stream")


if __name__ == "__main__":
    unittest.main()
