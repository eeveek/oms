package org.skyer.order.app.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.skyer.order.app.dto.BaseListDTO;
import org.skyer.order.app.service.saga.OrderForwardSagaService;
import org.skyer.order.infra.convertor.OrderItemLineConvertor;
import org.skyer.order.infra.feign.entity.generate.odo.in.OutboundDeliveryOrderDetailLineDTO;
import org.skyer.order.infra.feign.entity.generate.odo.in.OutboundDeliveryOrderInfoDTO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.skyer.boot.platform.code.builder.CodeRuleBuilder;
import org.skyer.boot.platform.code.constant.CodeConstants;
import org.skyer.boot.platform.lov.annotation.ProcessLovValue;
import org.skyer.core.exception.CommonException;
import org.skyer.mybatis.domian.Condition;
import org.skyer.mybatis.util.Sqls;
import org.skyer.order.api.dto.*;
import org.skyer.order.api.vo.*;
import org.skyer.order.app.dto.OutboundDeliveryOrderDTO;
import org.skyer.order.app.dto.ResultRecordDTO;
import org.skyer.order.app.service.*;
import org.skyer.order.domain.entity.*;
import org.skyer.order.domain.repository.*;
import org.skyer.order.infra.common.CommonConstants;
import org.skyer.order.infra.convertor.HeaderConvertor;
import org.skyer.order.infra.convertor.OrderItemConvertor;
import org.skyer.order.infra.convertor.ReceiverInfoConvertor;
import org.skyer.order.infra.enums.OrderItemEnum;
import org.skyer.order.infra.enums.OrderItemLineEnum;
import org.skyer.order.infra.enums.OrderStatusEnum;
import org.skyer.order.infra.enums.PriceTypeEnum;
import org.skyer.order.infra.feign.OnlineShopRemoteService;
import org.skyer.order.infra.feign.dto.OnlineShopDTO;
import org.skyer.order.infra.mapper.ItemLineMapper;
import org.skyer.order.infra.util.CollectorsUtil;
import org.springframework.util.ObjectUtils;

/**
 * ???????????????????????????????????????
 *
 * @author wushaochuan 2022-01-18 14:34:53
 */
@Service
@Slf4j
public class HeaderServiceImpl implements HeaderService {
    @Autowired
    private HeaderRepository headerRepository;
    @Autowired
    private ItemRepository itemRepository;
    @Autowired
    private ReceiverInfoRepository receiverInfoRepository;
    @Autowired
    private OnlineShopRemoteService onlineShopRemoteService;
    @Autowired
    private CodeRuleBuilder codeRuleBuilder;
    @Autowired
    private HeaderConvertor headerConvertor;
    @Autowired
    private OrderItemConvertor orderItemConvertor;
    @Autowired
    private PriceService priceService;
    @Autowired
    private PriceRepository priceRepository;
    @Autowired
    private OrderEsService orderEsService;
    @Autowired
    private ReceiverInfoConvertor receiverInfoConvertor;
    @Autowired
    private ItemLineMapper itemLineMapper;
    @Autowired
    private ItemPriceRepository itemPriceRepository;
    @Autowired
    private ItemLineRepository itemLineRepository;
    @Autowired
    private OrderItemLineConvertor orderItemLineConvertor;
    @Autowired
    private OrderForwardSagaService orderForwardSagaService;
    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    //todo ??????????????????
    public BaseListDTO<String, OrderItemDTO> createHandOrder(OrderTableDTO orderTableDTO) {
        checkQuantity(orderTableDTO.getOrderItemDTOList());
        checkOnlyOrder(orderTableDTO.getOrderBaseInfoDTO().getOuterOrderNo(), orderTableDTO.getOrderBaseInfoDTO().getBusinessType());
        //????????????
        checkOrderAmount(orderTableDTO.getOrderPaymentInfoDTO(), orderTableDTO.getOrderItemDTOList());
        //????????????
        checkChannel(orderTableDTO.getOrderBaseInfoDTO().getStoreId());
        //???????????????
        Header header = headerConvertor.dtoToHeader(orderTableDTO.getOrderBaseInfoDTO(), orderTableDTO.getOrderBuyerInfoDTO(), orderTableDTO.getOrderPaymentInfoDTO());
        header.setInnerOrderNo(codeRuleBuilder.generateCode(CodeConstants.Level.TENANT, 0L, CommonConstants.CodeRule.INNER_ORDER_NO, CodeConstants.CodeRuleLevelCode.CUSTOM, CommonConstants.LevelValue.INNER_ORDER_NO, null));
        priceService.saveOrderPrice(header, orderTableDTO.getOrderPaymentInfoDTO());
        headerRepository.insert(header);
        //?????????????????????
        ReceiverInfo receiverInfo = headerConvertor.dtoToReceiverInfo(orderTableDTO.getOrderBuyerInfoDTO());
        receiverInfo.setOuterOrderNo(header.getOuterOrderNo());
        receiverInfo.setInnerOrderNo(header.getInnerOrderNo());
        receiverInfo.setCountry("??????");
        receiverInfoRepository.insert(receiverInfo);
        BaseListDTO<String, OrderItemDTO> result = new BaseListDTO<>();
        result.setEntity(header.getInnerOrderNo());
        result.setList(orderTableDTO.getOrderItemDTOList());
        return result;
    }
    @ProcessLovValue
    public OrderInfoVO setLov(Header header) {
        return headerConvertor.headerToOrderInfoVO(header);
    }
    @Override
    public OrderTotalVO queryOrderTotal(Long orderId,String code) {
        OrderTotalVO orderTotalVO = new OrderTotalVO();
        Header header = null;
        if(!Objects.isNull(orderId)){
            header = headerRepository.selectByPrimaryKey(orderId);
        }else {
            header = headerRepository.queryHeaderByInnerOrderNo(code);
        }

        if (header == null) {
            throw new CommonException("??????????????????");
        }
        OrderInfoVO orderInfoVO = setLov(header);

        orderTotalVO.setOrderInfoVO(orderInfoVO);
        Map<String, ReceiverInfo> receiverInfoMap = receiverInfoRepository.queryReceiveInfoListByInner(Collections.singletonList(header.getInnerOrderNo()));
        ReceiverInfo receiverInfo = receiverInfoMap.get(header.getInnerOrderNo());
        ReceiverInfoVO receiverInfoVO = receiverInfoConvertor.receiverInfoToDto(receiverInfo);
        receiverInfoVO.setBuyerNick(header.getBuyerNick());
        orderTotalVO.setReceiverInfoVO(receiverInfoVO);
        Map<String, Price> priceMap = priceRepository.queryOrderPrice(header.getInnerOrderNo());
        PaymentInfoVO paymentInfoVO = headerConvertor.headerToPaymentVO(header);
        if (priceMap != null) {
            paymentInfoVO.setPayAmount(priceMap.get(PriceTypeEnum.BUYER_REAL_PRICE.getType()).getAmount());
            AmountInfoVO amountInfoVO = new AmountInfoVO();
            amountInfoVO.setBuyerPayAmount(priceMap.get(PriceTypeEnum.BUYER_NEED_PRICE.getType()).getAmount());
            amountInfoVO.setCostAmount(priceMap.get(PriceTypeEnum.COST_TOTAL_PRICE.getType()).getAmount());
            amountInfoVO.setGoodsTotalAmount(priceMap.get(PriceTypeEnum.GOODS_TOTAL_PRICE.getType()).getAmount());
            orderTotalVO.setAmountInfoVO(amountInfoVO);
        }
        orderTotalVO.setPaymentInfoVO(paymentInfoVO);
        orderTotalVO.setOrderStatusTotalVO(itemLineMapper.totalCurrentLineStatus(header.getInnerOrderNo()));
        orderTotalVO.setUnfinishedTotalVO(itemLineMapper.totalUnfinishedLineStatus(header.getInnerOrderNo()));
        orderTotalVO.setWaitDealTotalVO(itemLineMapper.totalWaitDealLineStatus(header.getInnerOrderNo()));
        return orderTotalVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void editOrderBase(OrderBaseDTO orderBaseDTO) {
        Header header = headerRepository.selectByPrimaryKey(orderBaseDTO.getOrderId());
        Header remarksHeader = headerConvertor.orderBaseToHeader(orderBaseDTO);
        if (Objects.nonNull(remarksHeader)) {
            remarksHeader.setId(header.getId());
            headerRepository.updateByPrimaryKeySelective(remarksHeader);
        }
        List<ItemLine> itemLineList = itemLineRepository.queryItemLineListByInner(header.getInnerOrderNo());
        List<ItemLine> allSourcingItemLine = itemLineList.stream().filter(x -> !String.valueOf(OrderItemLineEnum.SOURCING_WAIT.getCode()).equals(x.getSourceStatus())).collect(Collectors.toList());
        if (CollectionUtil.isNotEmpty(allSourcingItemLine)) {
            throw new CommonException("????????????????????????");
        }
        ReceiverInfo editReceiverInfo = receiverInfoRepository.queryReceiveInfoByInnerNo(header.getInnerOrderNo());
        ReceiverInfo receiverInfo = receiverInfoConvertor.dtoToReceiverInfo(orderBaseDTO.getReceiverInfoDTO());
        if (Objects.nonNull(receiverInfo)) {
            receiverInfo.setId(editReceiverInfo.getId());
            receiverInfoRepository.updateByPrimaryKeySelective(receiverInfo);
        }
        // TODO????????????????????????
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                // ??????es
                orderEsService.batchInsertOrderToEs(Collections.singletonList(header.getInnerOrderNo()));
            }
        });
    }

    private void checkQuantity(List<OrderItemDTO> orderItemDTOList) {
        for (OrderItemDTO orderItemDTO : orderItemDTOList) {
            if (ObjectUtil.isNull(orderItemDTO.getQuantity()) || orderItemDTO.getQuantity() <= 0) {
                throw new CommonException("???????????????????????????0?????????");
            }
        }
    }

    private void checkOnlyOrder(String outerOrderNo, String businessType) {
        Header header = headerRepository.queryHeaderByOutNoAndType(outerOrderNo, businessType);
        if (header != null) {
            throw new CommonException("??????????????????:??????????????????????????????");
        }

    }

    private void checkOrderAmount(OrderPaymentInfoDTO orderPaymentInfoDTO, List<OrderItemDTO> orderItemDTOList) {
        BigDecimal sumOrderItemAmount = orderItemDTOList.stream().collect(CollectorsUtil.summingBigDecimal(OrderItemDTO::getOuterSaleAmount));
        if (sumOrderItemAmount.compareTo(orderPaymentInfoDTO.getPayAmount()) != 0) {
            throw new CommonException("??????????????????:????????????????????????????????????????????????");
        }
    }

    private void checkChannel(Long storeId) {
        ResponseEntity<OnlineShopDTO> onlineShopDTOResponseEntity = onlineShopRemoteService.showDetails(storeId);
        if (onlineShopDTOResponseEntity == null) {
            throw new CommonException("??????????????????:????????????????????????????????????");
        }
        OnlineShopDTO body = onlineShopDTOResponseEntity.getBody();
        if (body == null || body.getStatus() == null || !body.getStatus()) {
            throw new CommonException("??????????????????:????????????????????????????????????");
        }
    }

    @Override
    public CopyOrderVO copyOrder(String innerOrderId) {
        List<Header> headers = headerRepository.selectByCondition(Condition.builder(Header.class)
                .andWhere(Sqls.custom().andEqualTo(Header.FIELD_INNER_ORDER_NO, innerOrderId)).build());
        if (CollUtil.isEmpty(headers)) {
            throw new CommonException("?????????????????????");
        }
        Header header = headers.get(0);
        CopyOrderVO orderVO = new CopyOrderVO();
        // ????????????
        OrderBaseInfoDTO baseInfoDTO = new OrderBaseInfoDTO();
        BeanUtils.copyProperties(header, baseInfoDTO);
        // ????????????
        OrderPaymentInfoDTO paymentInfoDTO = new OrderPaymentInfoDTO();
        BeanUtils.copyProperties(header, paymentInfoDTO);
        orderVO.setOrderBaseInfoDTO(baseInfoDTO);
        // ??????????????????
        Map<String, Price> priceMap = priceRepository.queryOrderPrice(header.getInnerOrderNo());
        Price price = priceMap.get(PriceTypeEnum.BUYER_REAL_PRICE.getType());
        if (Objects.nonNull(price)) {
            paymentInfoDTO.setPayAmount(price.getAmount());
        }
        orderVO.setOrderPaymentInfoDTO(paymentInfoDTO);
        List<ReceiverInfo> receiverInfos = receiverInfoRepository.selectByCondition(Condition.builder(ReceiverInfo.class)
                .andWhere(Sqls.custom().andEqualTo(ReceiverInfo.FIELD_INNER_ORDER_NO, innerOrderId)).build());
        if (CollUtil.isEmpty(receiverInfos)) {
            throw new CommonException("???????????????????????????");
        }
        ReceiverInfo receiverInfo = receiverInfos.get(0);
        OrderBuyerInfoDTO orderBuyerInfoDTO = new OrderBuyerInfoDTO();
        BeanUtils.copyProperties(receiverInfo, orderBuyerInfoDTO);
        orderBuyerInfoDTO.setBuyerRemarks(header.getBuyerRemarks());
        orderBuyerInfoDTO.setBuyerNick(header.getBuyerNick());
        orderVO.setOrderBuyerInfoDTO(orderBuyerInfoDTO);
        List<Item> items = itemRepository.queryItemByInner(header.getInnerOrderNo());
        // ??????????????????????????????
        items=items.stream().filter(x-> Boolean.FALSE.equals(Optional.ofNullable(x.getSystemPickFlag()).orElse(false))).collect(Collectors.toList());
        addCopyOrderItem(orderVO, items);
        return orderVO;
    }

    private void addCopyOrderItem(CopyOrderVO orderVO, List<Item> items) {
        if (CollUtil.isNotEmpty(items)) {
            List<CopyOrderItemVO> list = items.stream().map(item -> {
                CopyOrderItemVO itemVO=orderItemConvertor.dtoToCopyItem(item);
                itemVO.setSetMealFlag(item.getSetFlag());
                // ?????????????????????
                Map<String, ItemPrice> map = itemPriceRepository.queryItemPrice(item.getId());
                addCopyOrderItemPrice(item, itemVO, map);
                return itemVO;
            }).collect(Collectors.toList());
            orderVO.setItemVOList(list);
        }
    }

    private void addCopyOrderItemPrice(Item item, CopyOrderItemVO itemVO, Map<String, ItemPrice> map) {
        // ??????????????????
        ItemPrice outSaleAmount = map.get(PriceTypeEnum.OUT_SALE_PRICE.getType());
        if (Objects.nonNull(outSaleAmount)){
            if (Objects.isNull(outSaleAmount.getAmount())){
                throw new CommonException("?????????????????????????????????");
            }
            itemVO.setOuterSaleAmount(outSaleAmount.getAmount());
        }
        // ?????????
        ItemPrice costPrice = map.get(PriceTypeEnum.COST_TOTAL_PRICE.getType());
        if (Objects.nonNull(costPrice)){
            if (Objects.isNull(costPrice.getAmount())){
                throw new CommonException("????????????????????????");
            }
            itemVO.setCostPrice(costPrice.getAmount());
        }
        // ??????????????????
        if (Objects.nonNull(outSaleAmount)) {
            itemVO.setOuterSingleSaleAmount(outSaleAmount.getAmount().divide(new BigDecimal(item.getQuantity()), 2));
        }
        // ??????????????????
        ItemPrice innerSaleAmount= map.get(PriceTypeEnum.INNER_SALE_PRICE.getType());
        if (Objects.nonNull(innerSaleAmount)){
            if (Objects.isNull(innerSaleAmount.getAmount())){
                throw new CommonException("??????????????????????????????");
            }
            itemVO.setInnerSaleAmount(innerSaleAmount.getAmount());
        }
        // ??????????????????
        if (Objects.nonNull(innerSaleAmount)) {
            itemVO.setInnerSingleSaleAmount(innerSaleAmount.getAmount().divide(new BigDecimal(item.getQuantity()), 2));
        }
        // ??????????????????
        ItemPrice outBalancePrice = map.get(PriceTypeEnum.OUT_BALANCE_PRICE.getType());
        if (Objects.nonNull(outBalancePrice)){
            if (Objects.isNull(outBalancePrice.getAmount())){
                throw new CommonException("??????????????????????????????");
            }
            itemVO.setOutBalancePrice(outBalancePrice.getAmount());
        }
        // ??????????????????
        ItemPrice innerBalancePrice = map.get(PriceTypeEnum.INNER_BALANCE_PRICE.getType());
        if (Objects.nonNull(innerBalancePrice)){
            if (Objects.isNull(innerBalancePrice.getAmount())){
                throw new CommonException("??????????????????????????????");
            }
            itemVO.setInnerBalancePrice(innerBalancePrice.getAmount());
        }
        // ??????
        ItemPrice transPrice = map.get(PriceTypeEnum.TRANS_PRICE.getType());
        if (Objects.nonNull(transPrice)){
            if (Objects.isNull(transPrice.getAmount())){
                itemVO.setTransPrice(new BigDecimal(0));
            }
            itemVO.setTransPrice(transPrice.getAmount());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceipt(Long orderId) {
        Header header = headerRepository.selectByPrimaryKey(orderId);
        if (String.valueOf(OrderStatusEnum.RECEIVE_SUCCESS.getCode()).equals(header.getReceivingStatus()) ){
            throw new CommonException("???????????????");
        }
        // ??????????????????????????????????????????????????????????????????????????????
        if (!String.valueOf(OrderStatusEnum.SEND_SUCCESS.getCode()).equals(header.getDeliveryStatus()) && CommonConstants.EndorseStatus.SUCCESS.equals(header.getEndorseStatus())) {
            throw new CommonException("?????????????????????????????????");
        }
        // ???????????????
        List<ItemLine> lines = itemLineRepository.queryItemLineListByInner(header.getInnerOrderNo());
        // ????????????
        List<Item> items = itemRepository.queryItemByInner(header.getInnerOrderNo());
        // ?????????????????????
        List<ItemLine> itemLines = itemLineConfirmReceive(lines);
        // ??????????????????????????????????????????????????????
        syncConfirmItemAndOrderByItemLine(itemLines,items,header);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                // ??????es
                orderEsService.batchInsertOrderToEs(Collections.singletonList(header.getInnerOrderNo()));
            }
        });
    }


    private List<ItemLine> itemLineConfirmReceive(List<ItemLine> lines) {
        if (CollUtil.isNotEmpty(lines)) {
            // ??????????????????(?????????)???????????????
            return lines.stream().filter(x->!String.valueOf(OrderItemLineEnum.CANCEL.getCode()).equals(x.getCurrentStatus())).map(x -> {
                if (!String.valueOf(OrderItemLineEnum.SEND_SUCCESS.getCode()).equals(x.getDeliveryStatus())) {
                    throw new CommonException("?????????????????????????????????");
                }
                ItemLine line = new ItemLine();
                line.setId(x.getId());
                line.setReceivingStatus(String.valueOf(OrderItemLineEnum.RECEIVE_SUCCESS.getCode()));
                line.setCurrentStatus(CommonConstants.ItemLineStatus.COMPLETE);
                line.setInnerItemId(x.getInnerItemId());
                line.setDeliveryStatus(x.getDeliveryStatus());
                return line;
            }).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /**
     * ??????????????????????????????????????????????????????
     * @param lines
     */
    private void syncConfirmItemAndOrderByItemLine(List<ItemLine> lines,List<Item> items,Header header){
        if (CollUtil.isEmpty(lines)){
            throw new CommonException("???????????????????????????");
        }
        Map<Long, List<ItemLine>> itemLineByItemMap = lines.stream().collect(Collectors.groupingBy(ItemLine::getInnerItemId));
        // ???????????????????????????????????????
        items.forEach(x->{
            // ?????????????????????
            List<ItemLine> itemLines = itemLineByItemMap.get(x.getId());
            // ????????????????????????????????????
            long count = itemLines.stream().filter(itemLine -> Objects.nonNull(itemLine.getReceivingStatus())).
                    filter(itemLine -> !String.valueOf(OrderItemLineEnum.RECEIVE_SUCCESS.getCode()).equals(itemLine.getReceivingStatus())).count();
            // ???????????????????????????
            long waitCount = itemLines.stream().filter(itemLine -> Objects.nonNull(itemLine.getReceivingStatus())).
                    filter(itemLine -> String.valueOf(OrderItemLineEnum.RECEIVE_SUCCESS.getCode()).equals(itemLine.getReceivingStatus())).count();
            if (count==0){
                x.setReceivingStatus(String.valueOf(OrderItemEnum.RECEIVE_SUCCESS.getCode()));
            }else if (waitCount > 0 && waitCount!=itemLines.size()){
                x.setReceivingStatus(String.valueOf(OrderItemEnum.PART_RECEIVE_SUCCESS.getCode()));
            }else if (waitCount ==0){
                x.setReceivingStatus(String.valueOf(OrderItemEnum.RECEIVE_INIT.getCode()));
            }
        });
        // ?????????????????????????????????
        long count = items.stream().filter(item -> Objects.nonNull(item.getReceivingStatus())).
                filter(item -> !String.valueOf(OrderItemEnum.RECEIVE_SUCCESS.getCode()).equals(item.getReceivingStatus())).count();
        if (count==0){
            header.setReceivingStatus(String.valueOf(OrderStatusEnum.RECEIVE_SUCCESS.getCode()));
            header.setOrderStatus(CommonConstants.OrderStatus.Complete);
        }else {
            header.setReceivingStatus(String.valueOf(OrderStatusEnum.PART_RECEIVE_SUCCESS.getCode()));
        }

        // ?????????????????????
        itemLineRepository.batchUpdateByPrimaryKeySelective(lines);
        // ??????????????????
        itemRepository.batchUpdateByPrimaryKeySelective(items);
        headerRepository.updateByPrimaryKeySelective(header);
    }





    private void itemConfirmReceive(List<Item> items,Map<Long, List<ItemLine>> map) {
        if (CollUtil.isNotEmpty(items)) {
            List<Item> orderItems = items.stream().map(x -> {
                long count = map.get(x.getId()).stream().filter(item -> Objects.nonNull(item.getDeliveryStatus()) && !String.valueOf(OrderItemLineEnum.SEND_SUCCESS.getCode()).equals(item.getDeliveryStatus())).count();
                if (count>0){
                    throw new CommonException("???????????????????????????");
                }
                Item line = new Item();
                line.setId(x.getId());
                line.setReceivingStatus(String.valueOf(OrderItemEnum.RECEIVE_SUCCESS.getCode()));
                return line;
            }).collect(Collectors.toList());
            itemRepository.batchUpdateByPrimaryKeySelective(orderItems);
        }
    }

    /**
     * ??????????????????????????????????????????????????????
     * @param innerOrderNo
     * @return
     */
    private Map<Long, List<ItemLine>> queryByInnerOrderNo(String innerOrderNo) {
        // ???????????????????????????
        List<ItemLine> itemLines = itemLineMapper.selectByCondition(Condition.builder(ItemLine.class).andWhere(Sqls.custom().andEqualTo(ItemLine.FIELD_INNER_ORDER_NO, innerOrderNo)).build());
        if (CollUtil.isEmpty(itemLines)){
            throw new CommonException("???????????????????????????");
        }
        return itemLines.stream().collect(Collectors.groupingBy(ItemLine::getInnerItemId));
    }

    private Map<Long, List<Item>> queryByInnerOrderNoGroupById(String innerOrderNo) {
        // ???????????????????????????
        List<Item> items = itemRepository.selectByCondition(Condition.builder(Item.class).andWhere(Sqls.custom().andEqualTo(Item.FIELD_INNER_ORDER_NO, innerOrderNo)).build());
        if (CollUtil.isEmpty(items)){
            throw new CommonException("????????????????????????");
        }
        return items.stream().collect(Collectors.groupingBy(Item::getId));
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void itemConfirmReceipt(ItemConfirmDTO itemConfirmDTO) {
        // ???????????????
        Header header = headerRepository.queryHeaderByInnerOrderNo(itemConfirmDTO.getInnerOrderNo());
        // ????????????
        List<Item> items = itemRepository.queryItemByInner(header.getInnerOrderNo());
        // ??????????????????????????????
        if (CollUtil.isNotEmpty(itemConfirmDTO.getItemLineIds())){
            // ?????????????????????
            List<Long> itemLineIds = itemConfirmDTO.getItemLineIds();
            // ??????????????????????????????
            List<ItemLine> itemLines = itemLineRepository.queryItemLineByInnerList(Collections.singletonList(itemConfirmDTO.getInnerOrderNo()));
            // ?????????????????????????????????
            itemLines = itemLines.stream().filter(x->!String.valueOf(OrderItemLineEnum.CANCEL.getCode()).equals(x.getCurrentStatus())).collect(Collectors.toList());
            itemLines.forEach(x->{
                // ????????????itemLineIds??????
                if (itemLineIds.contains(x.getId())){
                    // ?????????????????????
                    if (!String.valueOf(OrderItemLineEnum.SEND_SUCCESS.getCode()).equals(x.getDeliveryStatus())) {
                        throw new CommonException("?????????????????????????????????");
                    }
                    x.setCurrentStatus(CommonConstants.ItemLineStatus.COMPLETE);
                    x.setReceivingStatus(String.valueOf(OrderItemLineEnum.RECEIVE_SUCCESS.getCode()));
                }
            });
            // ??????????????????????????????????????????????????????
            syncConfirmItemAndOrderByItemLine(itemLines,items,header);
        }else {
            // ??????itemId??????
            List<Long> itemIds = Lists.newArrayList();
            // ?????????ID??????
            List<Long> lineIds = Lists.newArrayList();
            List<IdListDTO> idListDTOS = itemConfirmDTO.getItemIds();
            idListDTOS.forEach(item->{
                itemIds.add(item.getId());
                // ?????????????????????????????????(?????????????????????ID)
                if (CollUtil.isNotEmpty(item.getIdList())) {
                     lineIds.addAll(item.getIdList());
                 }
            });

            // ??????????????????????????????
            List<ItemLine> itemLines = itemLineRepository.queryItemLineByInnerList(Collections.singletonList(itemConfirmDTO.getInnerOrderNo()));
            // ?????????????????????????????????
            itemLines = itemLines.stream().filter(x->!String.valueOf(OrderItemLineEnum.CANCEL.getCode()).equals(x.getCurrentStatus())).collect(Collectors.toList());
            itemLines.forEach(x->{
                // ????????????itemLineIds??????
                if (lineIds.contains(x.getId()) || itemIds.contains(x.getInnerItemId())){
                    // ?????????????????????
                    if (!String.valueOf(OrderItemLineEnum.SEND_SUCCESS.getCode()).equals(x.getDeliveryStatus())) {
                        throw new CommonException("?????????????????????????????????");
                    }
                    x.setCurrentStatus(CommonConstants.ItemLineStatus.COMPLETE);
                    x.setReceivingStatus(String.valueOf(OrderItemLineEnum.RECEIVE_SUCCESS.getCode()));
                }
            });
            // ??????????????????????????????????????????????????????
            syncConfirmItemAndOrderByItemLine(itemLines,items,header);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                // ??????es
                orderEsService.batchInsertOrderToEs(Collections.singletonList(itemConfirmDTO.getInnerOrderNo()));
            }
        });
    }
    private void syncItemAndOrderStatusByInnerOrderNO(String innerOrderNo,Map<Long, List<ItemLine>> itemLineMap){
        Map<Long, List<Item>> itemMap = queryByInnerOrderNoGroupById(innerOrderNo);
        List<Long> all = Lists.newArrayList();
        List<Long> part = Lists.newArrayList();
        List<Long> wait = Lists.newArrayList();
        itemMap.forEach((k,v)->{
            List<ItemLine> itemLines = itemLineMap.get(k);
            long count = itemLines.stream().filter(itemLine -> Objects.nonNull(itemLine.getReceivingStatus()) && String.valueOf(OrderItemLineEnum.RECEIVE_SUCCESS.getCode()).equals(itemLine.getReceivingStatus())).count();
            if (count == v.get(0).getQuantity()){
                all.add(k);
            }else if (count>0 && count < v.get(0).getQuantity()){
                part.add(k);
            }else {
                wait.add(k);
            }
            syncConfirmItem(wait);
            syncConfirmItem(all,true);
            syncConfirmItem(part,false);
        });
        Header header = headerRepository.queryHeaderByInnerOrderNo(innerOrderNo);
        Long goodsQuatity = header.getGoodsQuatity();
        AtomicLong sum = new AtomicLong(0);
        itemLineMap.forEach((k,v)->{
            long count = v.stream().filter(itemLine -> Objects.nonNull(itemLine.getReceivingStatus()) && String.valueOf(OrderItemLineEnum.RECEIVE_SUCCESS.getCode()).equals(itemLine.getReceivingStatus())).count();
            sum.addAndGet(count);
        });
        if (goodsQuatity == sum.get()){
            header.setReceivingStatus(String.valueOf(OrderStatusEnum.RECEIVE_SUCCESS.getCode()));
        }
        else if (sum.get()>0 && goodsQuatity >sum.get()){
            header.setReceivingStatus(String.valueOf(OrderStatusEnum.PART_RECEIVE_SUCCESS.getCode()));
        }else {
            header.setReceivingStatus(String.valueOf(OrderStatusEnum.RECEIVE_WAIT.getCode()));
        }
        headerRepository.updateByPrimaryKeySelective(header);
    }

    private void syncConfirmItem(List<Long> all) {
        if (CollUtil.isNotEmpty(all)) {
            List<Item> orderItems = all.stream().map(x -> {
                Item line = new Item();
                line.setId(x);
                line.setReceivingStatus(String.valueOf(OrderItemEnum.RECEIVE_WAIT.getCode()));
                return line;
            }).collect(Collectors.toList());
            itemRepository.batchUpdateByPrimaryKeySelective(orderItems);
        }
    }

    private void syncConfirmItem(List<Long> all, boolean b) {
        if (CollUtil.isNotEmpty(all)) {
            List<Item> orderItems = all.stream().map(x -> {
                Item line = new Item();
                line.setId(x);
                if (b) {
                    line.setReceivingStatus(String.valueOf(OrderItemEnum.RECEIVE_SUCCESS.getCode()));
                }else {
                    line.setReceivingStatus(String.valueOf(OrderItemEnum.PART_RECEIVE_SUCCESS.getCode()));
                }
                return line;
            }).collect(Collectors.toList());
            itemRepository.batchUpdateByPrimaryKeySelective(orderItems);
        }
    }


    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public Boolean outboundDeliveryOrderRetrography(OutboundDeliveryOrderDTO dto) {
        Header header = headerRepository.queryHeaderByInnerOrderNo(dto.getOrderCode());
        List<Long> itemLineIdList = dto.getDetailList().stream().flatMap(x -> x.getOrderGoodsLineIds().stream()).collect(Collectors.toList());
        List<ItemLine> itemLineList = itemLineRepository.queryItemLineByIds(itemLineIdList);
        itemLineList.forEach(itemLine -> {
            itemLine.setDeliveryStatus(String.valueOf(OrderItemLineEnum.SEND_WAIT.getCode()));
            itemLine.setCurrentStatus(String.valueOf(OrderItemLineEnum.SEND_WAIT.getCode()));
            itemLine.setDeliveryNoticeNo(dto.getCode());
        });

        List<Long> itemIdList = itemLineList.stream().map(ItemLine::getInnerItemId).collect(Collectors.toList());
        itemLineRepository.batchUpdateByPrimaryKeySelective(itemLineList);
        header.setDeliveryStatus(String.valueOf(OrderStatusEnum.SEND_WAIT.getCode()));
        List<Item> items = itemRepository.queryItemListByItemIds(itemIdList);
        items.forEach(item -> {
            item.setDeliveryStatus(String.valueOf(OrderItemEnum.SEND_WAIT.getCode()));
        });
        itemRepository.batchUpdateByPrimaryKeySelective(items);
        headerRepository.updateByPrimaryKeySelective(header);
        //????????????es????????????es
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                orderEsService.batchInsertOrderToEs(Collections.singletonList(header.getInnerOrderNo()));
            }});
        return true;
    }

    
    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public Boolean resultRecordRetrography(ResultRecordDTO dto) {
        //todo ????????????????????????????????????????????????wms????????????????????????????????????????????????????????????
        List<ItemLine> itemLineList = itemLineRepository.queryItemLineByPackNo(dto.getPackageCode());
        List<ItemLine> newItemLineList = itemLineList.stream().map(itemLine -> {
            ItemLine newItemLine = orderItemLineConvertor.resultRecordDTOToItemLine(dto);
            newItemLine.setDeliveryStatus(String.valueOf(OrderItemLineEnum.SEND_SUCCESS.getCode()));
            newItemLine.setCurrentStatus(String.valueOf(OrderItemLineEnum.SEND_SUCCESS.getCode()));
            newItemLine.setReceivingStatus(String.valueOf(OrderItemLineEnum.RECEIVE_WAIT.getCode()));
            newItemLine.setId(itemLine.getId());
            return newItemLine;
        }).collect(Collectors.toList());
        itemLineRepository.batchUpdateByPrimaryKeySelective(newItemLineList);
        //??????item???header??????
        List<Long> itemIdList = itemLineList.stream().map(ItemLine::getInnerItemId).collect(Collectors.toList());
        itemIdList.forEach(this::dealItemSendStatus);
        dealHeaderSendStatus(dto.getOrderCode());
        //????????????es????????????es
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                orderEsService.batchInsertOrderToEs(Collections.singletonList(dto.getOrderCode()));
            }});
        return true;
    }

    @Override
    public List<AccountCheckingOrderDTO> getOrderList(AccountCheckingOrderQueryDTO dto) {
        return headerRepository.getOrderList(dto);
    }

    @Override
    public List<AccountCheckingOrderDTO> getAfterSaleList(AccountCheckingOrderQueryDTO dto) {
        return headerRepository.getAfterSaleList(dto);
    }


    public void dealItemSendStatus(Long itemId) {
        List<ItemLine> itemLines = itemLineRepository.queryItemLineByItemId(itemId);
        Item item = itemRepository.selectByPrimaryKey(itemId);
        List<ItemLine> itemStatusLine = itemLines.stream().filter(itemLine -> !itemLine.getDeliveryStatus().equals(String.valueOf(OrderItemLineEnum.SEND_SUCCESS.getCode()))).collect(Collectors.toList());
        if (CollUtil.isEmpty(itemStatusLine)) {
            item.setDeliveryStatus(String.valueOf(OrderItemEnum.SEND_SUCCESS.getCode()));
            item.setReceivingStatus(String.valueOf(OrderItemEnum.RECEIVE_WAIT.getCode()));
        }else{
            item.setDeliveryStatus(String.valueOf(OrderItemEnum.SEND_PART_SUCCESS.getCode()));
        }
        itemRepository.updateByPrimaryKeySelective(item);
    }

    @Override
    public List<RpcOrderInfoVO> batchGetOrderPrice(List<String> batchStringListDTO) {
        List<RpcOrderInfoVO> list= Lists.newArrayList();
        Map<String, ReceiverInfo> receiverInfoMap = receiverInfoRepository.queryReceiveInfoListByInner(batchStringListDTO);
        List<Price> prices = priceRepository.queryOrderPriceByList(batchStringListDTO);
        if (CollUtil.isEmpty(receiverInfoMap) || CollUtil.isEmpty(prices)) {
            log.error("remote call getOrderPrice error, batchStringListDTO:{}", batchStringListDTO);
            throw new CommonException("?????????????????????????????????????????????");
        }
        receiverInfoMap.forEach((k,v)->{
            List<RpcOrderAmountVO> rpcOrderAmountVOList = Lists.newArrayList();
            RpcOrderInfoVO rpcOrderInfoVO = new RpcOrderInfoVO();
            rpcOrderInfoVO.setOrderCode(k);
            rpcOrderInfoVO.setProvinceCode(v.getProvinceCode());
            rpcOrderInfoVO.setCityCode(v.getCityCode());
            rpcOrderInfoVO.setAreaCode(v.getDistrictCode());
            List<Price> priceList = prices.stream().filter(price -> price.getInnerOrderNo().equals(k)).collect(Collectors.toList());
            if (CollUtil.isNotEmpty(priceList)) {
                priceList.forEach(price -> {
                    RpcOrderAmountVO rpcOrderAmountVO = new RpcOrderAmountVO();
                    rpcOrderAmountVO.setAmount(price.getAmount());
                    rpcOrderAmountVO.setType(price.getType());
                    rpcOrderAmountVOList.add(rpcOrderAmountVO);
                });
            }
            rpcOrderInfoVO.setAmountList(rpcOrderAmountVOList);
            list.add(rpcOrderInfoVO);
        });
        return list;
    }

    public void dealHeaderSendStatus(String innerOrder) {
        Header header = headerRepository.queryHeaderByInnerOrderNo(innerOrder);
        List<ItemLine> itemLines = itemLineRepository.queryItemLineListByInner(innerOrder);
        List<ItemLine> itemStatusLine = itemLines.stream().filter(itemLine -> !itemLine.getDeliveryStatus().equals(String.valueOf(OrderItemLineEnum.SEND_SUCCESS.getCode()))).collect(Collectors.toList());
        if (CollUtil.isEmpty(itemStatusLine)) {
            header.setDeliveryStatus(String.valueOf(OrderStatusEnum.SEND_SUCCESS.getCode()));
        }else{
            header.setDeliveryStatus(String.valueOf(OrderStatusEnum.SEND_PART_SUCCESS.getCode()));
        }
        headerRepository.updateByPrimaryKeySelective(header);

    }


    @Override
    public void createSendNotice(String innerOrderCode) {
        OutboundDeliveryOrderInfoDTO dto = new OutboundDeliveryOrderInfoDTO();
        // ?????????????????????
        ReceiverInfo receiverInfo = receiverInfoRepository.queryReceiveInfoByInnerNo(innerOrderCode);
        dto.setOrderCode(innerOrderCode);
        dto.setReceiverName(receiverInfo.getReceiverName());
        dto.setReceiverCountry(receiverInfo.getCountry());
        dto.setReceiverProvince(receiverInfo.getProvince());
        dto.setReceiverCity(receiverInfo.getCity());
        dto.setReceiverDistrict(receiverInfo.getDistrict());
        dto.setReceiverAddress(receiverInfo.getAddress());
        dto.setReceiverTel(receiverInfo.getMobile());
        dto.setReceiverMobile(receiverInfo.getPhone());
        Header header = headerRepository.queryHeaderByInnerOrderNo(innerOrderCode);
        dto.setPlatformOrderCode(header.getOuterOrderNo());
        dto.setSorBuyerNikeName(header.getBuyerNick());
        dto.setOrderMsg(header.getOrderRemarks());
        dto.setBuyMsg(header.getBuyerRemarks());
        dto.setOrderId(header.getId());
        dto.setShopName(header.getStoreName());
        dto.setChannelTypeCode(header.getChannel());
        dto.setShopId(header.getStoreId());
        dto.setShopCode(header.getStoreCode());
        dto.setScheduledDeliveryTime(header.getPredictDeliveryTime());
        dto.setTenantId(0L);
        //??????item
        List<Item> items = itemRepository.queryItemByInner(innerOrderCode);
        List<OutboundDeliveryOrderDetailLineDTO> outDtoList = Lists.newArrayList();
        items.forEach(item -> {
            item.setDeliveryStatus(String.valueOf(OrderItemEnum.SEND_SUCCESS.getCode()));
            OutboundDeliveryOrderDetailLineDTO outDto = new OutboundDeliveryOrderDetailLineDTO();
            outDto.setSkuCode(item.getSkuCode());
            outDto.setSpuCode(item.getSpuCode());
            outDto.setSkuName(item.getSkuName());
            outDto.setSpuName(item.getSpuName());
            List<ItemLine> itemLineList = itemLineRepository.queryItemLineByItemId(item.getId());
            outDto.setOrderGoodsLineIds(itemLineList.stream().map(ItemLine::getId).collect(Collectors.toList()));
            outDtoList.add(outDto);
        });
        dto.setDetailList(outDtoList);
        //?????????????????????
        header.setDeliveryStatus(String.valueOf(OrderStatusEnum.SEND_SUCCESS.getCode()));
        orderForwardSagaService.generateOutboundDeliveryOrder(dto);

    }
}
