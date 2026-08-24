package com.liverepublic.server.broadcast

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ivs.IvsClient
import software.amazon.awssdk.services.ivs.model.ChannelLatencyMode
import software.amazon.awssdk.services.ivs.model.ChannelType

data class IvsChannel(
    val channelArn: String,
    val ingestEndpoint: String,
    val streamKey: String,
    val playbackUrl: String,
)

/** IVS Channel 관리. 테스트에서는 Stub으로 대체한다. */
interface IvsService {
    fun createChannel(name: String): IvsChannel
    fun stopStream(channelArn: String)

    /** 현재 송출 중인 Stream Session 식별자. 송출이 없으면 null. */
    fun currentStreamSessionId(channelArn: String): String?
}

/**
 * 실제 AWS IVS 연동. Channel Type은 BASIC, 송출 720p/30fps (2026-08-24 사람 결정).
 * 자격증명은 표준 AWS 환경변수(AWS_ACCESS_KEY_ID 등)로 주입된다.
 */
class AwsIvsService(private val region: String) : IvsService {

    private val client: IvsClient by lazy {
        IvsClient.builder().region(Region.of(region)).build()
    }

    override fun createChannel(name: String): IvsChannel {
        val response = client.createChannel { builder ->
            builder.name(name)
                .type(ChannelType.BASIC)
                .latencyMode(ChannelLatencyMode.LOW)
        }
        val channel = response.channel()
        return IvsChannel(
            channelArn = channel.arn(),
            ingestEndpoint = "rtmps://${channel.ingestEndpoint()}:443/app/",
            streamKey = response.streamKey().value(),
            playbackUrl = channel.playbackUrl(),
        )
    }

    override fun stopStream(channelArn: String) {
        try {
            client.stopStream { it.channelArn(channelArn) }
        } catch (e: software.amazon.awssdk.services.ivs.model.ChannelNotBroadcastingException) {
            // 이미 송출이 끊긴 경우 — 종료 처리에는 문제 없다.
        }
    }

    override fun currentStreamSessionId(channelArn: String): String? = try {
        client.getStream { it.channelArn(channelArn) }.stream().streamId()
    } catch (e: software.amazon.awssdk.services.ivs.model.ChannelNotBroadcastingException) {
        null
    }
}

@Configuration
class IvsConfig {

    /** 테스트 프로필에서는 등록하지 않는다 (테스트가 Stub Bean을 제공). */
    @Bean
    @Profile("!test")
    fun ivsService(@Value("\${aws.region:ap-northeast-2}") region: String): IvsService = AwsIvsService(region)
}
