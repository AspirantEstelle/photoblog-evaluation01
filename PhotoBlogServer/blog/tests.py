import io
import shutil
import tempfile
from PIL import Image
from django.contrib.auth import get_user_model
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase, override_settings
from django.urls import reverse
from django.utils import timezone
from rest_framework.test import APIClient
from rest_framework.authtoken.models import Token
from .models import Post


def picture(name='example.png'):
    stream = io.BytesIO()
    Image.new('RGB', (32, 32), '#3974b9').save(stream, 'PNG')
    return SimpleUploadedFile(name, stream.getvalue(), content_type='image/png')


class PhotoBlogTests(TestCase):
    @classmethod
    def setUpClass(cls):
        cls.media = tempfile.mkdtemp()
        cls.override = override_settings(MEDIA_ROOT=cls.media)
        cls.override.enable()
        super().setUpClass()

    @classmethod
    def tearDownClass(cls):
        super().tearDownClass()
        cls.override.disable()
        shutil.rmtree(cls.media)

    def setUp(self):
        self.owner = get_user_model().objects.create_user('owner', password='test-pass-123')
        self.other = get_user_model().objects.create_user('other', password='test-pass-456')
        self.post = Post.objects.create(author=self.owner, title='첫 사진', text='본문',
                                       published_date=timezone.now(), image=picture())
        self.api = APIClient()

    def login_api(self, user=None):
        token, _ = Token.objects.get_or_create(user=user or self.owner)
        self.api.credentials(HTTP_AUTHORIZATION='Token ' + token.key)

    def test_list_is_array_with_absolute_image_url(self):
        result = self.api.get('/api_root/Post/')
        self.assertEqual(result.status_code, 200)
        self.assertIsInstance(result.json(), list)
        self.assertEqual(result.json()[0]['title'], '첫 사진')
        self.assertTrue(result.json()[0]['image'].startswith('http://testserver/media/'))

    def test_detail_and_not_found(self):
        self.assertEqual(self.api.get(f'/api_root/Post/{self.post.pk}/').status_code, 200)
        self.assertEqual(self.api.get('/api_root/Post/99999/').status_code, 404)

    def test_anonymous_write_rejected(self):
        result = self.api.post('/api_root/Post/', {'title': 'anonymous', 'text': 'x', 'image': picture()})
        self.assertEqual(result.status_code, 401)

    def test_multipart_create_saves_image_and_owner(self):
        self.login_api()
        result = self.api.post('/api_root/Post/', {'title': 'Android 업로드', 'text': 'UTF-8 본문',
                            'author': self.other.pk, 'image': picture()}, format='multipart')
        self.assertEqual(result.status_code, 201, result.data)
        created = Post.objects.get(pk=result.data['id'])
        self.assertEqual(created.author, self.owner)
        self.assertIsNotNone(created.published_date)
        with created.image.open('rb') as saved:
            self.assertEqual(Image.open(saved).size, (32, 32))

    def test_missing_image_and_blank_title(self):
        self.login_api()
        result = self.api.post('/api_root/Post/', {'title': '', 'text': 'x'})
        self.assertEqual(result.status_code, 400)
        self.assertIn('title', result.data)
        self.assertIn('image', result.data)

    def test_invalid_image_rejected(self):
        self.login_api()
        fake = SimpleUploadedFile('fake.png', b'not an image', content_type='image/png')
        result = self.api.post('/api_root/Post/', {'title': 'fake', 'text': 'x', 'image': fake})
        self.assertEqual(result.status_code, 400)

    def test_oversized_and_unsupported_image(self):
        from .serializers import PostSerializer
        from django.core.exceptions import ValidationError
        large = picture()
        large.size = 6 * 1024 * 1024
        with self.assertRaises(ValidationError):
            PostSerializer().validate_image(large)
        data = io.BytesIO()
        Image.new('RGB', (8, 8)).save(data, 'BMP')
        self.login_api()
        result = self.api.post('/api_root/Post/', {'title': 'bmp', 'text': 'x',
                 'image': SimpleUploadedFile('a.bmp', data.getvalue(), content_type='image/bmp')})
        self.assertEqual(result.status_code, 400)

    def test_real_oversized_upload_rejected_by_web_and_api(self):
        self.login_api()
        self.client.force_login(self.owner)
        payload = picture().read() + b'\0' * (5 * 1024 * 1024)
        for client, url in [(self.api, '/api_root/Post/'), (self.client, '/post/new/')]:
            with self.subTest(url=url):
                response = client.post(url, {'title': 'large', 'text': '본문',
                    'image': SimpleUploadedFile('large.png', payload, content_type='image/png')})
                self.assertEqual(response.status_code, 400 if client is self.api else 200)
                self.assertContains(response, '5 MB', status_code=response.status_code)
        self.assertEqual(Post.objects.count(), 1)

    def test_web_rejects_image_format_the_app_cannot_display(self):
        self.client.force_login(self.owner)
        data = io.BytesIO()
        Image.new('RGB', (8, 8)).save(data, 'BMP')
        response = self.client.post('/post/new/', {'title': 'bmp', 'text': '본문',
            'image': SimpleUploadedFile('photo.bmp', data.getvalue(), content_type='image/bmp')})
        self.assertContains(response, 'JPG, PNG, GIF, WEBP')
        self.assertEqual(Post.objects.count(), 1)

    def test_owner_can_update_and_delete(self):
        self.login_api()
        url = f'/api_root/Post/{self.post.pk}/'
        self.assertEqual(self.api.patch(url, {'title': '수정'}).status_code, 200)
        self.assertEqual(self.api.delete(url).status_code, 204)
        self.assertFalse(Post.objects.filter(pk=self.post.pk).exists())

    def test_other_author_cannot_update_or_delete(self):
        self.login_api(self.other)
        url = f'/api_root/Post/{self.post.pk}/'
        self.assertEqual(self.api.patch(url, {'title': 'x'}).status_code, 403)
        self.assertEqual(self.api.delete(url).status_code, 403)

    def test_staff_can_manage(self):
        self.other.is_staff = True
        self.other.save()
        self.login_api(self.other)
        self.assertEqual(self.api.patch(f'/api_root/Post/{self.post.pk}/', {'title': '관리'}).status_code, 200)

    def test_search_and_options(self):
        self.assertEqual(len(self.api.get('/api_root/Post/?search=없는검색').json()), 0)
        self.assertEqual(len(self.api.get('/api_root/Post/?search=사진').json()), 1)
        self.assertEqual(self.api.options('/api_root/Post/').status_code, 200)
        self.assertEqual(self.api.head('/api_root/Post/').status_code, 200)

    def test_token_login(self):
        result = self.api.post('/api-token-auth/', {'username': 'owner', 'password': 'test-pass-123'})
        self.assertEqual(result.status_code, 200)
        self.assertIn('token', result.data)
        self.assertEqual(self.api.post('/api-token-auth/', {'username': 'owner', 'password': 'bad'}).status_code, 400)

    def test_web_list_search_detail(self):
        self.assertContains(self.client.get('/'), '첫 사진')
        self.assertNotContains(self.client.get('/?q=없는검색'), '첫 사진')
        self.assertContains(self.client.get(f'/post/{self.post.pk}/'), '<img', html=False)

    def test_web_create_edit_and_permissions(self):
        self.assertEqual(self.client.get('/post/new/').status_code, 302)
        self.client.force_login(self.owner)
        self.assertContains(self.client.get('/post/new/'), 'multipart/form-data')
        self.assertEqual(self.client.post('/post/new/', {'title': '웹 업로드', 'text': '본문', 'image': picture()}).status_code, 302)
        url = f'/post/{self.post.pk}/edit/'
        self.assertEqual(self.client.get(url).status_code, 200)
        self.assertEqual(self.client.post(url, {'title': '웹 수정', 'text': '바뀐 본문'}).status_code, 302)
        self.client.force_login(self.other)
        self.assertEqual(self.client.get(url).status_code, 403)

    def test_model_publish_and_string(self):
        self.post.published_date = None
        self.post.publish()
        self.assertEqual(str(self.post), '첫 사진')
        self.assertIsNotNone(self.post.published_date)
