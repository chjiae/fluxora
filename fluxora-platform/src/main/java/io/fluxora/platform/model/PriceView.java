package io.fluxora.platform.model;

import java.time.Instant;

/** 价格接口精确字符串投影；禁止以 JavaScript Number 传输或比较账务价格。 */
public class PriceView { private Long id; private String currencyCode,inputPrice,outputPrice,cacheWritePrice,cacheReadPrice,sourceType; private Instant effectiveAt,expiresAt,createdAt;
 public Long getId(){return id;}public void setId(Long v){id=v;}public String getCurrencyCode(){return currencyCode;}public void setCurrencyCode(String v){currencyCode=v;}public String getInputPrice(){return inputPrice;}public void setInputPrice(String v){inputPrice=v;}public String getOutputPrice(){return outputPrice;}public void setOutputPrice(String v){outputPrice=v;}public String getCacheWritePrice(){return cacheWritePrice;}public void setCacheWritePrice(String v){cacheWritePrice=v;}public String getCacheReadPrice(){return cacheReadPrice;}public void setCacheReadPrice(String v){cacheReadPrice=v;}public String getSourceType(){return sourceType;}public void setSourceType(String v){sourceType=v;}public Instant getEffectiveAt(){return effectiveAt;}public void setEffectiveAt(Instant v){effectiveAt=v;}public Instant getExpiresAt(){return expiresAt;}public void setExpiresAt(Instant v){expiresAt=v;}public Instant getCreatedAt(){return createdAt;}public void setCreatedAt(Instant v){createdAt=v;}}
