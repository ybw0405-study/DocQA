package com.docqa.reminder.service;

/**
 * 上传的 ICS 文件。控制器负责把 multipart 部分取成字节，service 只接受这个形态，
 * 因此解析与判定逻辑不依赖任何 web 层类型。
 *
 * <p>数组不做防御性复制：{@code MultipartFile.getBytes()} 每次都返回新数组，
 * 而 2 MiB 的文件会被指纹与解析各读一次，复制两次只是白扔内存。
 *
 * @param contentType {@code file} 部分的 Content-Type，允许为 null（浏览器常常不给）
 * @param content 文件原始字节
 */
public record IcsUpload(String contentType, byte[] content) {
}
