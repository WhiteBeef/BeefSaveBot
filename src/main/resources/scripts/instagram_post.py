"""Описание поста Instagram для бота: картинки, видео и музыка.

yt-dlp не отдаёт ни картинки фото-постов, ни их музыку, но сам умеет достать данные поста
в обход ограничений для анонимных запросов. Поэтому перехватываем сырые данные поста внутри
его экстрактора Instagram и печатаем нужное одной строкой JSON.

Использование: python instagram_post.py <ссылка> [--cookies файл] [--user-agent строка]
"""
import json
import sys

import yt_dlp
from yt_dlp.extractor.instagram import InstagramBaseIE

MUSIC_URL_KEYS = ('progressive_download_url', 'fast_start_progressive_download_url')


def best_image(media):
    candidates = ((media.get('image_versions2') or {}).get('candidates')) or []
    candidates = [c for c in candidates if isinstance(c, dict) and c.get('url')]
    if not candidates:
        return media.get('display_uri') or media.get('display_url')
    best = max(candidates, key=lambda c: (c.get('width') or 0) * (c.get('height') or 0))
    return best['url']


def best_video(media):
    versions = [v for v in (media.get('video_versions') or []) if isinstance(v, dict) and v.get('url')]
    if not versions:
        return None
    return max(versions, key=lambda v: (v.get('width') or 0) * (v.get('height') or 0))['url']


def item(media):
    video = best_video(media)
    if video:
        return {'type': 'video', 'url': video}
    image = best_image(media)
    return {'type': 'image', 'url': image} if image else None


def find_key(node, keys):
    """Первое значение по любому из ключей где угодно внутри node."""
    if isinstance(node, dict):
        for key in keys:
            if node.get(key) not in (None, ''):
                return node[key]
        children = node.values()
    elif isinstance(node, list):
        children = node
    else:
        return None
    for child in children:
        found = find_key(child, keys)
        if found not in (None, ''):
            return found
    return None


def music(product):
    for key in ('music_metadata', 'clips_metadata', 'music_info'):
        subtree = product.get(key)
        url = find_key(subtree, MUSIC_URL_KEYS)
        if url:
            return {
                'url': url,
                'start_ms': find_key(subtree, ('audio_asset_start_time_in_ms',)),
                'duration_ms': find_key(subtree, ('overlap_duration_in_ms', 'duration_in_ms')),
                'title': find_key(subtree, ('title', 'song_name')),
                'artist': find_key(subtree, ('display_artist', 'artist_name')),
            }
    return None


def main():
    args = sys.argv[1:]
    url = args[0]
    options = {
        'quiet': True,
        'no_warnings': True,
        'skip_download': True,
        'ignore_no_formats_error': True,
    }
    if '--cookies' in args:
        options['cookiefile'] = args[args.index('--cookies') + 1]
    if '--user-agent' in args:
        options['http_headers'] = {'User-Agent': args[args.index('--user-agent') + 1]}

    captured = []
    original = InstagramBaseIE._extract_product

    def capture(self, product_info, *rest, **kwargs):
        captured.append(product_info[0] if isinstance(product_info, list) else product_info)
        return original(self, product_info, *rest, **kwargs)

    InstagramBaseIE._extract_product = capture
    error = None
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            ydl.extract_info(url, download=False, process=False)
    except Exception as e:  # данные поста могли успеть перехватить до ошибки
        error = str(e)
    if not captured:
        print(json.dumps({'error': error or 'Instagram не отдал данные поста'}, ensure_ascii=False))
        sys.exit(2)

    product = captured[0]
    media = product.get('carousel_media') or [product]
    caption = (product.get('caption') or {}).get('text') if isinstance(product.get('caption'), dict) else None
    print(json.dumps({
        'title': caption,
        'username': (product.get('user') or {}).get('username'),
        'items': [i for i in (item(m) for m in media if isinstance(m, dict)) if i],
        'music': music(product),
    }, ensure_ascii=False))


if __name__ == '__main__':
    main()
