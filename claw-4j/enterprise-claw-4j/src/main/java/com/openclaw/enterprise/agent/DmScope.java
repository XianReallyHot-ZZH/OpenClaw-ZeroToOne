package com.openclaw.enterprise.agent;

/**
 * DM (Direct Message) 会话隔离粒度枚举
 *
 * <p>定义 Agent 的会话隔离级别 — 不同级别的隔离粒度决定了
 * 会话 ID (session key) 的构造方式，从而影响会话的共享范围。</p>
 *
 * <p>隔离粒度从粗到细：</p>
 * <table>
 *   <tr><th>Scope</th><th>Session Key 格式</th><th>示例</th></tr>
 *   <tr>
 *     <td>{@link #MAIN}</td>
 *     <td>{@code agent:{agentId}:main}</td>
 *     <td>{@code agent:luna:main}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #PER_PEER}</td>
 *     <td>{@code agent:{agentId}:peer:{peerId}}</td>
 *     <td>{@code agent:luna:peer:12345}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #PER_CHANNEL_PEER}</td>
 *     <td>{@code agent:{agentId}:{channel}:{peerId}}</td>
 *     <td>{@code agent:luna:telegram:12345}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #PER_ACCOUNT_CHANNEL_PEER}</td>
 *     <td>{@code agent:{agentId}:{accountId}:{channel}:{peerId}}</td>
 *     <td>{@code agent:luna:bot1:telegram:12345}</td>
 *   </tr>
 * </table>
 *
 * <p>claw0 参考: s05_gateway_routing.py 中 DmScope 枚举和 session key 构造逻辑</p>
 */
public enum DmScope {

    /**
     * 全局共享 — 所有用户共享同一个会话
     *
     * <p>适用于公开频道、广播场景。</p>
     */
    MAIN,

    /**
     * 按对话方隔离 — 同一用户在不同渠道共享会话
     *
     * <p>同一 peerId 的用户无论通过哪个渠道接入，都共享同一个会话。</p>
     */
    PER_PEER,

    /**
     * 按渠道+对话方隔离 — 不同渠道独立会话
     *
     * <p>同一用户在 Telegram 和飞书上分别有独立的会话。</p>
     */
    PER_CHANNEL_PEER,

    /**
     * 按账号+渠道+对话方隔离 — 最细粒度
     *
     * <p>适用于多 Bot 账号场景，同一用户在不同 Bot 账号下有独立会话。</p>
     */
    PER_ACCOUNT_CHANNEL_PEER
}
