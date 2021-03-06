package org.skyer.order.app.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.compress.utils.Lists;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.skyer.boot.platform.code.builder.CodeRuleBuilder;
import org.skyer.boot.platform.code.constant.CodeConstants;
import org.skyer.core.domain.Page;
import org.skyer.core.exception.CommonException;
import org.skyer.mybatis.domian.Condition;
import org.skyer.mybatis.util.Sqls;
import org.skyer.order.api.dto.ItemSearchDTO;
import org.skyer.order.api.dto.StatsStockoutDTO;
import org.skyer.order.api.vo.*;
import org.skyer.order.app.dto.*;
import org.skyer.order.app.service.*;
import org.skyer.order.app.vo.WorkExecuteVO;
import org.skyer.order.domain.entity.*;
import org.skyer.order.domain.repository.*;
import org.skyer.order.infra.common.CommonConstants;
import org.skyer.order.infra.convertor.OrderItemLineConvertor;
import org.skyer.order.infra.enums.OrderItemEnum;
import org.skyer.order.infra.enums.OrderItemLineEnum;
import org.skyer.order.infra.enums.OrderStatusEnum;
import org.skyer.order.infra.enums.PriceTypeEnum;
import org.skyer.order.infra.feign.GoodsSkuRemoteService;
import org.skyer.order.infra.feign.GoodsStockRemoteService;
import org.skyer.order.infra.feign.vo.RpcGoodsResVO;
import org.skyer.order.infra.feign.vo.SkuUsableStockVO;
import org.skyer.order.infra.util.CollectorsUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ?????????????????????????????????????????????
 *
 * @author wushaochuan 2022-01-18 14:34:54
 */
@Service
@Log4j2
public class ItemLineServiceImpl implements ItemLineService {
    @Autowired
    private ItemSnapshotRepository itemSnapshotRepository;
    @Autowired
    private OrderItemLineConvertor orderItemLineConvertor;
    @Autowired
    private ItemRepository itemRepository;
    @Autowired
    private HeaderRepository headerRepository;
    @Autowired
    private ItemSnapshotService itemSnapshotService;
    @Autowired
    private ItemLineRepository itemLineRepository;
    @Autowired
    private OrderEsService orderEsService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private ItemPriceRepository itemPriceRepository;
    @Autowired
    private ItemLinePriceService itemLinePriceService;
    @Autowired
    private CodeRuleBuilder codeRuleBuilder;
    @Autowired
    private GoodsSkuRemoteService goodsSkuRemoteService;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private ItemPriceService itemPriceService;
    @Autowired
    private GoodsStockRemoteService goodsStockRemoteService;

    @Override
    public WorkExecuteVO splitItemLineByLock(String innerOrderNo) {
        WorkExecuteVO workExecuteVO = new WorkExecuteVO();
        RLock rlock = redissonClient.getLock("rds_split_lock" + innerOrderNo);
        try {
            boolean res = rlock.tryLock(5, 10, TimeUnit.SECONDS);
            if (res) {
                workExecuteVO = splitItemLine(innerOrderNo);
            } else {
                log.error("???????????????");
            }
        } catch (InterruptedException e) {
            throw new CommonException("???????????????????????????:" + innerOrderNo);
        } finally {
            rlock.unlock();
        }
        return workExecuteVO;
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public WorkExecuteVO splitItemLine(String innerOrderNo) {
        Header header = headerRepository.queryHeaderByInnerOrderNo(innerOrderNo);
        List<Item> itemList = itemRepository.queryItemByInner(innerOrderNo);
        //???itemList????????????
        itemPriceService.setItemPrice(itemList);
        //???????????????????????????????????????
        List<ItemLine> haveItemLineList = itemLineRepository.queryItemLineListByInner(innerOrderNo);
        if (CollectionUtil.isNotEmpty(haveItemLineList)) {
            return new WorkExecuteVO();
        }
        WorkExecuteVO workExecuteVO = new WorkExecuteVO();
        //?????????????????????
        List<String> skuCodeList = itemList.parallelStream().map(Item::getSkuCode).collect(Collectors.toList());
        Map<String, ItemSnapshot> skuSnapshotMap = itemSnapshotRepository.querySnapshot(innerOrderNo, skuCodeList);
        //??????????????????????????????
        List<String> errorSkuList = itemSnapshotService.checkItemSnapshot(header, skuCodeList, skuSnapshotMap);
        if (CollectionUtils.isNotEmpty(errorSkuList)) {
            //?????????????????????????????? -- ?????????????????????????????????,???????????????--???????????????????????????
            workExecuteVO.setCode(CommonConstants.WorkCode.SPLIT_ITEM_LINE_ERROR);
            workExecuteVO.setMessage("?????????????????????");
            workExecuteVO.setResult(JSONObject.toJSONString(errorSkuList));
            return workExecuteVO;
        }
        List<ItemLine> itemLineList = Lists.newArrayList();
        List<Item> notSetItemList = itemList.stream().filter(item -> item.getSetFlag() == 0).collect(Collectors.toList());
        if (CollectionUtil.isNotEmpty(notSetItemList)) {
            itemLineList.addAll(notSetItemList.parallelStream().flatMap(item -> Stream.iterate(0L, x -> x + 1)
                    .limit(item.getQuantity()).map(x -> {
                        ItemLine itemLine = orderItemLineConvertor.itemToItemLine(item, skuSnapshotMap.get(item.getSkuCode()));
                        itemLine.setItemLineCode(codeRuleBuilder.generateCode(CodeConstants.Level.TENANT, 0L, CommonConstants.CodeRule.ITEM_LINE_CODE, CodeConstants.CodeRuleLevelCode.CUSTOM, CommonConstants.LevelValue.ITEM_LINE_NO, null));
                        itemLine.setCostPrice(skuSnapshotMap.get(item.getSkuCode()).getCostPrice());
                        itemLine.setOuterSaleAmount(item.getOuterSingleSaleAmount());
                        itemLine.setInnerSaleAmount(item.getInnerSingleSaleAmount());
                        itemLine.setInnerItemId(item.getId());
                        itemLine.setId(null);
                        return itemLine;
                    })).collect(Collectors.toList()));
            //????????????
            splitItemLinePrice(itemLineList, notSetItemList);
        }
        //?????????????????????????????????
        //??????itemLine????????????????????????????????????
        itemLineList.addAll(splitMealItemLine(itemList));
        itemList.forEach(item -> item.setPreemptionStatus(String.valueOf(OrderItemEnum.PREEMPTION_WAIT.getCode())));
        header.setGoodsQuatity((long) itemLineList.size());
        header.setPreemptionStatus(String.valueOf(OrderStatusEnum.PREEMPTION_WAIT.getCode()));
        itemLineRepository.batchInsert(itemLineList);
        itemRepository.batchUpdateByPrimaryKeySelective(itemList);
        headerRepository.updateByPrimaryKeySelective(header);
        itemLinePriceService.saveItemLinePrice(itemLineList);
        orderEsService.batchInsertOrderToEs(Collections.singletonList(header.getInnerOrderNo()));
        workExecuteVO.setResult(header.getBusinessType());
        return workExecuteVO;
    }

    /**
     * ??????????????????????????????
     *
     * @param items
     */
    private List<ItemLine> splitMealItemLine(List<Item> items) {
        List<ItemLine> itemLineList = Lists.newArrayList();
        //??????
        List<Item> setItemList = items.stream().filter(itemLine -> Optional.ofNullable(itemLine.getSetFlag()).orElse(0) == 1).collect(Collectors.toList());
        if (CollectionUtil.isEmpty(setItemList)) {
            return Lists.newArrayList();
        }
        List<String> skuCodeList = setItemList.stream().map(Item::getSkuCode).distinct().collect(Collectors.toList());
        ResponseEntity<List<RpcGoodsResVO>> mealsGoodsListEntity = goodsSkuRemoteService.getMealsGoodsList(skuCodeList);
        log.info("????????????????????????????????????????????? ??????{},??????{}", JSONObject.toJSONString(skuCodeList), JSONObject.toJSONString(mealsGoodsListEntity));
        if (CollectionUtil.isEmpty(mealsGoodsListEntity.getBody())) {
            throw new CommonException("????????????????????????????????????");
        }
        List<RpcGoodsResVO> rpcGoodsResVoList = mealsGoodsListEntity.getBody();
        Map<String, List<RpcGoodsResVO>> mainSkuRpcVOMap = rpcGoodsResVoList.stream().collect(Collectors.groupingBy(RpcGoodsResVO::getMainSkuCode));
        //?????????????????????
        setItemList.forEach(item -> {
            List<RpcGoodsResVO> rpcGoodsResVOS = mainSkuRpcVOMap.get(item.getSkuCode());
            if (CollectionUtil.isEmpty(rpcGoodsResVOS)) {
                throw new CommonException("????????????skuCde" + item.getSkuCode() + "?????????????????????????????????????????????");
            }
            List<ItemLine> packItemLineList = rpcGoodsResVOS.parallelStream().flatMap(rpcGoodsResVO -> Stream.iterate(0L, x -> x + 1)
                    .limit(rpcGoodsResVO.getNum()).map(x -> {
                        ItemLine itemLine = orderItemLineConvertor.rpcItemToItemLine(item, rpcGoodsResVO);
                        itemLine.setItemLineCode(codeRuleBuilder.generateCode(CodeConstants.Level.TENANT, 0L, CommonConstants.CodeRule.ITEM_LINE_CODE, CodeConstants.CodeRuleLevelCode.CUSTOM, CommonConstants.LevelValue.ITEM_LINE_NO, null));
                        itemLine.setInnerItemId(item.getId());
                        itemLine.setId(null);
                        return itemLine;
                    })).collect(Collectors.toList());
            //???????????????????????????????????????
            splitItemLinePrice(packItemLineList, Collections.singletonList(item));
            itemLineList.addAll(packItemLineList);
        });
        return itemLineList;
    }

    /**
     * ????????????
     *
     * @param itemLines
     */
    private void splitItemLinePrice(List<ItemLine> itemLines, List<Item> items) {
        Map<Long, List<ItemLine>> itemLineMap = itemLines.parallelStream().collect(Collectors.groupingBy(ItemLine::getInnerItemId));
        List<PackItemAmountDTO> packItemAmountDTOList = items.parallelStream().map(item -> {
            //????????????????????????dto
            Map<String, ItemPrice> itemPriceMap = itemPriceRepository.queryItemPrice(item.getId());
            List<ItemLine> itemItemLineList = itemLineMap.get(item.getId());
            PackItemAmountDTO packItemAmountDTO = new PackItemAmountDTO();
            packItemAmountDTO.setItemId(item.getId());
            packItemAmountDTO.setPackBalancePrice(itemPriceMap.get(PriceTypeEnum.OUT_BALANCE_PRICE.getType()).getAmount());
            packItemAmountDTO.setOutTotalSplitPrice(itemItemLineList.stream().collect(CollectorsUtil.summingBigDecimal(ItemLine::getOuterSaleAmount)));
            packItemAmountDTO.setInnerTotalSplitPrice(itemItemLineList.stream().collect(CollectorsUtil.summingBigDecimal(ItemLine::getInnerSaleAmount)));
            List<SplitItemAmountDTO> splitItemAmountDTOList = itemItemLineList.stream().map(itemLine -> {
                SplitItemAmountDTO splitItemAmountDTO = new SplitItemAmountDTO();
                splitItemAmountDTO.setCodeOrId(String.valueOf(itemLine.getItemLineCode()));
                splitItemAmountDTO.setSkuCode(itemLine.getSkuCode());
                splitItemAmountDTO.setInnerPrice(itemLine.getInnerSaleAmount());
                splitItemAmountDTO.setOutPrice(itemLine.getOuterSaleAmount());
                splitItemAmountDTO.setNum(1);
                splitItemAmountDTO.setIsGift(itemLine.getGiftFlag());
                return splitItemAmountDTO;
            }).collect(Collectors.toList());
            packItemAmountDTO.setSplitItemAmountDTOList(splitItemAmountDTOList);
            return packItemAmountDTO;
        }).collect(Collectors.toList());
        //?????????????????????
        List<SplitItemAmountDTO> outSplitItemAmountDTOList = orderService.splitPackAmount(packItemAmountDTOList, false, 1);
        Map<String, SplitItemAmountDTO> outSplitItemAmountMap = outSplitItemAmountDTOList.stream().collect(Collectors.toMap(SplitItemAmountDTO::getCodeOrId, Function.identity(), (o1, o2) -> o1));
        packItemAmountDTOList.forEach(packItemAmountDTO -> {
            Map<String, ItemPrice> itemPriceMap = itemPriceRepository.queryItemPrice(packItemAmountDTO.getItemId());
            packItemAmountDTO.setPackBalancePrice(itemPriceMap.get(PriceTypeEnum.INNER_BALANCE_PRICE.getType()).getAmount());
        });
        //?????????????????????
        List<SplitItemAmountDTO> innerSplitItemAmountDTOList = orderService.splitPackAmount(packItemAmountDTOList, true, 2);
        Map<String, SplitItemAmountDTO> innerSplitItemAmountMap = innerSplitItemAmountDTOList.stream().collect(Collectors.toMap(SplitItemAmountDTO::getCodeOrId, Function.identity(), (o1, o2) -> o1));
        itemLines.forEach(itemLine -> {
            SplitItemAmountDTO outSplitItemAmountDTO = outSplitItemAmountMap.get(String.valueOf(itemLine.getItemLineCode()));
            SplitItemAmountDTO innerSplitItemAmountDTO = innerSplitItemAmountMap.get(String.valueOf(itemLine.getItemLineCode()));
            itemLine.setInnerBalanceAmount(innerSplitItemAmountDTO.getInnerBalancePrice());
            itemLine.setOutBalanceAmount(outSplitItemAmountDTO.getOutBalancePrice());
        });
    }

    @Override
    public Page<OrderItemLinePageVO> queryItemLinePage(ItemSearchDTO itemSearchDTO) {
        return itemLineRepository.queryItemLinePage(itemSearchDTO);
    }


    @Override
    public List<StatsStockoutVO> statsStockout(StatsStockoutDTO dto) {

        Condition.Builder builder = Condition.builder(ItemLine.class)
                .where(Sqls.custom()
                        .andEqualTo(ItemLine.FIELD_PREEMPTION_STATUS, String.valueOf(OrderItemLineEnum.PREEMPTION_FAIL.getCode()))
                        .andEqualTo(ItemLine.FIELD_SKU_CODE, dto.getSkuCode(), true)
                        .andIn(ItemLine.FIELD_SKU_CODE, dto.getSkuCodeList(), true)
                        .andIn(ItemLine.FIELD_SPU_CODE, dto.getSpuCodeList(), true));

        // ??????????????????
        List<ItemLine> lines = itemLineRepository.selectByCondition(builder.build());

        Map<String, List<ItemLine>> skuGroupMap = lines.stream().collect(
                Collectors.groupingBy(ItemLine::getSkuCode));

        List<String> skuCodeList = new ArrayList<>(skuGroupMap.keySet());
        Map<String, BigDecimal> skuUsableStockMap = rpcSkuUsableStock(skuCodeList);

        return skuGroupMap.values().stream()
                .map(this::statsStockoutQty)
                .peek(x -> {
                    // ??????????????????
                    BigDecimal usable = skuUsableStockMap.get(x.getSkuCode());
                    x.setUsableQty(usable == null ? 0L : usable.longValue());
                })
                .collect(Collectors.toList());

    }

    private Map<String, BigDecimal> rpcSkuUsableStock(List<String> skuCodeList) {

        ResponseEntity<List<SkuUsableStockVO>> response = goodsStockRemoteService.statsSkuUsableStock(skuCodeList);
        if (response.getStatusCode() == HttpStatus.OK) {
            List<SkuUsableStockVO> skuUsableStockVOList = response.getBody();
            if (ObjectUtils.isEmpty(skuUsableStockVOList)) {
                return Collections.emptyMap();
            }
            return skuUsableStockVOList.stream().collect(
                    Collectors.toMap(SkuUsableStockVO::getSkuCode, SkuUsableStockVO::getUsableNum));
        }

        throw new CommonException("????????????sku??????????????????");
    }

    /**
     * ??????????????????????????????????????????
     * @param itemLineList sku??????????????????
     * @return ????????????????????????????????????
     */
    private StatsStockoutVO statsStockoutQty(List<ItemLine> itemLineList) {
        ItemLine itemLine = itemLineList.get(0);

        Long orderQty = itemLineList.stream().map(ItemLine::getInnerOrderNo).distinct().count();
        long goodsQty = itemLineList.size();

        StatsStockoutVO vo = new StatsStockoutVO();
        vo.setStockoutGoodsQty(goodsQty);
        vo.setStockoutOrderQty(orderQty);
        vo.setSpuCode(itemLine.getSpuCode());
        vo.setSpuName(itemLine.getSpuName());
        vo.setSkuCode(itemLine.getSkuCode());

        return vo;
    }

}
