package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.PythonAskRequest;
import com.docqa.agentgateway.dto.PythonAskResponse;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

public interface PythonAgentClient {

    PythonAskResponse ask(PythonAskRequest request, String traceId);

    /**
     * 打开流式问答：阻塞到 Python 响应头返回（2xx 即承诺事件流），失败按首字节前语义抛
     * {@link PythonCallException}；返回的 Flux 需由调用方订阅，done 帧载荷经
     * {@link #parseStreamPayload(String)} 还原为 {@link PythonAskResponse}。
     */
    Flux<ServerSentEvent<String>> openStream(PythonAskRequest request, String traceId);

    /** 解析流式 done 帧（或 error 帧之外的事件载荷）为完整响应，载荷缺字段按协议违例处理。 */
    PythonAskResponse parseStreamPayload(String data);
}
