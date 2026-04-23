package com.canggo.zhishu.repository;

import co.elastic.clients.elasticsearch.nodes.Ingest;
import com.canggo.zhishu.model.ChunkInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    List<ChunkInfo> findByFileMd5OrderByChunkIndexAsc(String fileMd5);
    //删除本次相同filemd5的数据
    void deleteByFileMd5(String fileMd5);
}
