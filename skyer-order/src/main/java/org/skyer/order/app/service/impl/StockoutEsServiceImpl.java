package org.skyer.order.app.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.search.*;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.skyer.boot.platform.lov.annotation.ProcessLovValue;
import org.skyer.core.domain.Page;
import org.skyer.core.exception.CommonException;
import org.skyer.mybatis.domian.Condition;
import org.skyer.mybatis.util.Sqls;
import org.skyer.order.api.dto.StatsStockoutDTO;
import org.skyer.order.api.es.OrderEsModel;
import org.skyer.order.api.es.SearchStockoutEsParam;
import org.skyer.order.api.es.SpuEsModel;
import org.skyer.order.api.vo.StatsStockoutVO;
import org.skyer.order.api.vo.StockoutPageVo;
import org.skyer.order.app.service.ItemLineService;
import org.skyer.order.app.service.StockoutEsService;
import org.skyer.order.domain.entity.ItemLine;
import org.skyer.order.domain.entity.ReceiverInfo;
import org.skyer.order.domain.repository.ItemLineRepository;
import org.skyer.order.domain.repository.ReceiverInfoRepository;
import org.skyer.order.infra.common.CommonConstants;
import org.skyer.order.infra.enums.OrderItemLineEnum;
import org.skyer.order.infra.util.EsBoolQueryUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.validation.constraints.NotEmpty;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author: lzh
 * @date: 2022-03-07
 * @description:
 */
@Service
@Slf4j
public class StockoutEsServiceImpl implements StockoutEsService {

    @Autowired
    private RestHighLevelClient client;

    @Autowired
    private ReceiverInfoRepository receiverInfoRepository;

    @Autowired
    private ItemLineRepository itemLineRepository;
    @Autowired
    private ItemLineService itemLineService;

    @Override
    @ProcessLovValue
    public Page<StockoutPageVo> stockoutPage(SearchStockoutEsParam searchStockoutEsParam) throws Exception {
        // ???????????????????????????(????????????????????????????????????)
        List<ItemLine> lines = itemLineRepository.selectByCondition(Condition.builder(ItemLine.class)
                .andWhere(Sqls.custom().andEqualTo(ItemLine.FIELD_PREEMPTION_STATUS, String.valueOf(OrderItemLineEnum.PREEMPTION_FAIL.getCode()))).build());
        // ?????????sku????????? ???????????????SKU???????????????
        if (ObjectUtil.isNotEmpty(searchStockoutEsParam.getSkuCode())) {
            lines = lines.stream()
                    .filter(line -> line.getSkuCode().equals(searchStockoutEsParam.getSkuCode()))
                    .filter(line -> String.valueOf(OrderItemLineEnum.PREEMPTION_FAIL.getCode()).equals(line.getPreemptionStatus()))
                    .collect(Collectors.toList());
        }
        if (CollUtil.isEmpty(lines)) {
            return new Page<>();
        }
        Page<StockoutPageVo> vos = new Page<>();
        int size = searchStockoutEsParam.getSize();
        vos.setSize(size);
        vos.setNumber(searchStockoutEsParam.getPage() + 1);
        // ???????????????????????????
        if (!isExistsIndex(CommonConstants.EsConstant.ORDER_INDEX)) {
            return new Page<>();
        }
        try {
            // ??????Es????????????
            SearchRequest searchRequest = buildSearchRequest(searchStockoutEsParam, lines);
            // ??????????????????
            SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);
            SearchHits hits = searchResponse.getHits();
            long totalElements = hits.getTotalHits().value;
            int total = (int) totalElements;
            int page = total / size;
            // ????????????hits??????
            List<StockoutPageVo> stockoutPageVos = buildSearchResult(searchResponse);
            vos.setContent(stockoutPageVos);
            vos.setTotalElements(totalElements);
            vos.setTotalPages(page);
        } catch (IOException e) {
            log.info(e.getMessage());
        }
        return vos;
    }

    @Override
    public List<StatsStockoutVO> statsStockout(StatsStockoutDTO dto) {

        // es????????????spu?????????sku??????
        if (ObjectUtil.isNotEmpty(dto.getQuerySpu())) {
            try {
                // ???????????????????????????
                if (!isExistsIndex(CommonConstants.EsConstant.GOODS_SPU_INDEX)) {
                    return Lists.newArrayList();
                }

                List<String> spuCodeList = this.searchAllSpuCodeByQuerySpu(dto.getQuerySpu());
                if (CollUtil.isEmpty(spuCodeList)) {
                    return Lists.newArrayList();
                }

                if (CollUtil.isNotEmpty(dto.getSpuCodeList())) {
                    spuCodeList.addAll(dto.getSpuCodeList());
                }
                dto.setSpuCodeList(spuCodeList);
            } catch (Exception e) {
                throw new CommonException("es?????????????????????????????????", e);
            }
        }

        return itemLineService.statsStockout(dto);
    }

    /**
     * ????????????????????????spuCode,????????????spu???????????????
     * @param querySpu spu??????????????????
     * @return spuCode??????
     * @throws IOException es io??????
     */
    private List<String> searchAllSpuCodeByQuerySpu(@NotEmpty String querySpu) throws IOException {
        // ??????bool-query
        BoolQueryBuilder builder = QueryBuilders.boolQuery();
        List<String> arrayList = Lists.newArrayList();
        String[] arr = new String[]{"spuCode", "name"};
        arrayList.add(querySpu);
        builder = EsBoolQueryUtil.inMultiFieldPhraseQuery(builder, arrayList, arr);

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(builder);
        searchSourceBuilder.size(10000);

        searchSourceBuilder.fetchSource("spuCode", null);

        List<String> spuCodeList = new ArrayList<>();
        final Scroll scroll = new Scroll(TimeValue.timeValueMinutes(1L));

        SearchRequest searchRequest = new SearchRequest(CommonConstants.EsConstant.GOODS_SPU_INDEX);
        searchRequest.source(searchSourceBuilder);
        searchRequest.scroll(scroll);

        SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);

        String scrollId = searchResponse.getScrollId();

        SearchHit[] searchHits = searchResponse.getHits().getHits();
        while (searchHits != null && searchHits.length > 0) {
            SearchHits hits = searchResponse.getHits();

            List<String> tmpList = Arrays.stream(hits.getHits())
                    .map(x -> JSON.parseObject(x.getSourceAsString()).toJavaObject(SpuEsModel.class))
                    .filter(Objects::nonNull)
                    .map(SpuEsModel::getSpuCode)
                    .distinct()
                    .collect(Collectors.toList());
            spuCodeList.addAll(tmpList);

            SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
            scrollRequest.scroll(scroll);

            searchResponse = client.scroll(scrollRequest, RequestOptions.DEFAULT);
            searchHits = searchResponse.getHits().getHits();
        }
        // ????????????
        if (scrollId != null) {
            ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
            clearScrollRequest.addScrollId(scrollId);
            ClearScrollResponse clearScrollResponse = client.clearScroll(clearScrollRequest, RequestOptions.DEFAULT);
            clearScrollResponse.isSucceeded();
        }

        return spuCodeList.stream().distinct().collect(Collectors.toList());
    }

    /**
     * ??????????????????
     *
     * @param searchStockoutEsParam
     * @return
     */
    private SearchRequest buildSearchRequest(SearchStockoutEsParam searchStockoutEsParam, List<ItemLine> lines) {
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        // ??????bool-query
        BoolQueryBuilder builder = QueryBuilders.boolQuery();
        builder = getBoolQueryBuilder(builder, searchStockoutEsParam, lines);
        // ???????????????????????????
        searchSourceBuilder.query(builder);
        // ??????
        searchSourceBuilder.sort("creationDate", SortOrder.DESC);
        // ??????
        EsBoolQueryUtil.page(searchSourceBuilder, searchStockoutEsParam.getPage(), searchStockoutEsParam.getSize());
        searchSourceBuilder.trackTotalHits(true);
        return new SearchRequest(new String[]{CommonConstants.EsConstant.ORDER_INDEX}, searchSourceBuilder);
    }

    /**
     * ???????????????
     *
     * @param response
     * @return
     */
    private List<StockoutPageVo> buildSearchResult(SearchResponse response) {
        List<StockoutPageVo> resultList = Lists.newArrayList();
        //1????????????????????????????????????
        SearchHits hits = response.getHits();
        if (hits.getHits() != null && hits.getHits().length > 0) {
            for (SearchHit hit : hits.getHits()) {
                StockoutPageVo pageVo = new StockoutPageVo();
                OrderEsModel orderEsModel = JSON.parseObject(hit.getSourceAsString()).toJavaObject(OrderEsModel.class);
                BeanUtils.copyProperties(orderEsModel, pageVo);
                pageVo.setTagsList(orderEsModel.getTagsList());
                resultList.add(pageVo);
            }
            // ??????????????????
            List<String> collect = resultList.stream().map(StockoutPageVo::getInnerOrderNo).collect(Collectors.toList());
            Map<String, List<ItemLine>> listMap = itemLineRepository.queryItemLineByInner(collect);
            for (StockoutPageVo pageVo : resultList) {
                // ???????????????????????????
                long count = listMap.get(pageVo.getInnerOrderNo()).stream().filter(x -> Objects.nonNull(x.getPreemptionStatus()))
                        .filter(x -> String.valueOf(OrderItemLineEnum.PREEMPTION_FAIL.getCode()).equals(x.getPreemptionStatus())).count();
                pageVo.setStockoutNum((int) count);
            }
        }
        return resultList;
    }


    /**
     * ????????????
     *
     * @return
     */
    public BoolQueryBuilder getBoolQueryBuilder(BoolQueryBuilder builder, SearchStockoutEsParam searchStockoutEsParam, List<ItemLine> lines) {
        // ????????????
        if (Objects.nonNull(lines)) {
            List<String> collect = lines.stream().filter(x -> Objects.nonNull(x.getInnerOrderNo())).map(ItemLine::getInnerOrderNo).collect(Collectors.toList());
            builder = EsBoolQueryUtil.inStringQuery(builder, "innerOrderNo", collect);
        }
        if (Objects.nonNull(searchStockoutEsParam.getOrderNo())) {
            List<String> orderNoItem = Arrays.asList(searchStockoutEsParam.getOrderNo().split(","));
            String[] arr = new String[]{"outerOrderNo", "innerOrderNo"};
            builder = EsBoolQueryUtil.inMultiFieldPhraseQuery(builder, orderNoItem, arr);
        }
        if (Objects.nonNull(searchStockoutEsParam.getChannel())) {
            builder.must(QueryBuilders.termQuery("channel", searchStockoutEsParam.getChannel()));
        }
        if (Objects.nonNull(searchStockoutEsParam.getStoreIdList())) {
            builder = EsBoolQueryUtil.inTermQuery(builder, "storeId", searchStockoutEsParam.getStoreIdList());
        }

        if (Objects.nonNull(searchStockoutEsParam.getSpuName())) {
            List<String> arrayList = Lists.newArrayList();
            String[] arr = new String[]{"orderItemLineList.spuCode", "orderItemLineList.spuName"};
            arrayList.add(searchStockoutEsParam.getSpuName());
            builder = EsBoolQueryUtil.inMultiFieldPhraseQuery(builder, arrayList, arr);
        }
//        if (Objects.nonNull(searchStockoutEsParam.getSkuCode())) {
//            builder = EsBoolQueryUtil.andStringQuery(builder, "orderItemLineList.skuCode", searchStockoutEsParam.getSkuCode());
//        }
        if (Objects.nonNull(searchStockoutEsParam.getActionType())) {
            builder.must(QueryBuilders.termQuery("actionType", searchStockoutEsParam.getActionType()));
        }
        if (Objects.nonNull(searchStockoutEsParam.getIsGift())) {
            builder.must(QueryBuilders.termQuery("isGift", searchStockoutEsParam.getIsGift()));
        }
        if (Objects.nonNull(searchStockoutEsParam.getBuyerNick())) {
            builder.must(QueryBuilders.matchPhraseQuery("buyerNick", searchStockoutEsParam.getBuyerNick()));
        }
        if (Objects.nonNull(searchStockoutEsParam.getReceiverName())) {
            builder.must(QueryBuilders.matchPhraseQuery("receiverName", searchStockoutEsParam.getReceiverName()));
        }
        if (Objects.nonNull(searchStockoutEsParam.getMobile())) {
            builder.must(QueryBuilders.termQuery("mobile", searchStockoutEsParam.getMobile()));
        }
        if (Objects.nonNull(searchStockoutEsParam.getBuyerRemarks())) {
            builder.must(QueryBuilders.matchPhraseQuery("buyerRemarks", searchStockoutEsParam.getBuyerRemarks()));
        }
        if (Objects.nonNull(searchStockoutEsParam.getOrderRemarks())) {
            builder.must(QueryBuilders.matchPhraseQuery("orderRemarks", searchStockoutEsParam.getOrderRemarks()));
        }
        if (Objects.nonNull(searchStockoutEsParam.getIsBuyerRemarks())) {
            builder.must(QueryBuilders.matchPhraseQuery("isBuyerRemarks", searchStockoutEsParam.getIsBuyerRemarks()));
        }
        if (Objects.nonNull(searchStockoutEsParam.getIsOrderRemarks())) {
            builder.must(QueryBuilders.matchPhraseQuery("isOrderRemarks", searchStockoutEsParam.getIsOrderRemarks()));
        }

        if (Objects.nonNull(searchStockoutEsParam.getOrderStartTime()) && Objects.nonNull(searchStockoutEsParam.getOrderEndTime())) {
            builder.must(QueryBuilders.rangeQuery("orderTime").gte(searchStockoutEsParam.getOrderStartTime().getTime()).lt(searchStockoutEsParam.getOrderEndTime().getTime()));
        }
        if (Objects.nonNull(searchStockoutEsParam.getPayStartTime()) && Objects.nonNull(searchStockoutEsParam.getPayEndTime())) {
            builder.must(QueryBuilders.rangeQuery("payTime").gte(searchStockoutEsParam.getPayStartTime().getTime()).lt(searchStockoutEsParam.getPayEndTime().getTime()));
        }
        if (Objects.nonNull(searchStockoutEsParam.getCreationStartDate()) && Objects.nonNull(searchStockoutEsParam.getCreationEndDate())) {
            builder.must(QueryBuilders.rangeQuery("creationDate").gte(searchStockoutEsParam.getCreationStartDate().getTime()).lt(searchStockoutEsParam.getCreationEndDate().getTime()));
        }
        if (Objects.nonNull(searchStockoutEsParam.getPredictDeliveryStartTime()) && Objects.nonNull(searchStockoutEsParam.getPredictDeliveryEndTime())) {
            builder.must(QueryBuilders.rangeQuery("predictDeliveryTime").gte(searchStockoutEsParam.getPredictDeliveryStartTime().getTime()).lt(searchStockoutEsParam.getPredictDeliveryEndTime().getTime()));
        }
        return builder;
    }


    /**
     * ??????????????????????????????
     *
     * @param indexName
     * @return
     */
    private boolean isExistsIndex(String indexName) throws Exception {
        GetIndexRequest request = new GetIndexRequest(indexName);
        return client.indices().exists(request, RequestOptions.DEFAULT);

    }
}
