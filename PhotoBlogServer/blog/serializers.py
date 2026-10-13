from rest_framework import serializers
from .models import Post

class PostSerializer(serializers.ModelSerializer):
    author_name = serializers.CharField(source='author.username', read_only=True)
    image = serializers.ImageField(required=True)

    class Meta:
        model = Post
        fields = ('id', 'author', 'author_name', 'title', 'text', 'created_date', 'published_date', 'image')
        read_only_fields = ('id', 'author', 'created_date')

    def validate_image(self, value):
        if value.size > 5 * 1024 * 1024:
            raise serializers.ValidationError('이미지는 5 MB 이하여야 합니다.')
        if value.image.format not in ('JPEG', 'PNG', 'GIF', 'WEBP'):
            raise serializers.ValidationError('JPG, PNG, GIF, WEBP 이미지를 선택하세요.')
        return value
