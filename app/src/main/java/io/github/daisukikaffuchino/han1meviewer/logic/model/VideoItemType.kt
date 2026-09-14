package io.github.daisukikaffuchino.han1meviewer.logic.model

interface VideoItemType {
    val title: String
    val coverUrl: String
    val videoCode: String
    val duration: String?
    val views: String?
    val reviews: String?
    val currentArtist: String?
    val uploadTime: String?

    /**
     * **无码**（26.8）：nJAV 的 `-uncensored-leak` 片、hanime 带「無修正」标签的片。
     *
     * 放在接口上而不是 `HanimeInfo` 上，是因为列表卡片统一收 [VideoItemType]；
     * 给默认实现（false）就不会逼着每个实现类都加构造参数。
     */
    val isUncensored: Boolean get() = false
}