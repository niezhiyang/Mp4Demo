# Mp4Demo
- MediaMuxer：是用来来封装编码后的视频流和音频流到mp4容器中：
- MediaExtractor：用来从mp4 ，或者 mp3 中提取 音视频的
- MediaCodec 用来使用dps编解码 音视频 数据的
##  裁剪mp3
- 把mp3 用 MediaExtractor 解封装
- 定位到 音频的 轨道，
- seek到对应的解码时间
- 使用 MediaCodec  解出来 pcm
- 然后用工具类，把 pcm 封装成 wav 即 mp3 格式

- 两个 mp3 合并
## 给视频添加背景音乐
1. 拿到背景音乐的 pcm
2. 拿到 mp4 中的 pcm
3. 合并两个pcm
4. 然后把 pcm 转成 wav（mp3）
5. 然后把 wav 压缩成 aac
6. 使用 MediaMuxer 添加 两个轨道 一个是视频，一个是 合成的音频 ，写到一个mp4中

