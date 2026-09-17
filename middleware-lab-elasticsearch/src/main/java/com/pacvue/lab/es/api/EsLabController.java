package com.pacvue.lab.es.api;

import com.pacvue.lab.common.probe.ProbeResult;
import com.pacvue.lab.es.domain.ProductDoc;
import com.pacvue.lab.es.service.EsProbeService;
import com.pacvue.lab.es.service.ProductIndexService;
import com.pacvue.lab.es.service.ProductSearchService;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manual poking surface for exploratory runs; the automated suite does not use it. */
@RestController
@RequestMapping("/api/es")
public class EsLabController {

    private final EsProbeService probeService;
    private final ProductIndexService indexService;
    private final ProductSearchService searchService;

    public EsLabController(EsProbeService probeService,
                           ProductIndexService indexService,
                           ProductSearchService searchService) {
        this.probeService = probeService;
        this.indexService = indexService;
        this.searchService = searchService;
    }

    @GetMapping("/ping")
    public ProbeResult ping() {
        return probeService.ping();
    }

    @PostMapping("/index/recreate")
    public ResponseEntity<Void> recreateIndex() {
        indexService.recreateIndex();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/products")
    public ProductDoc save(@RequestBody ProductDoc doc) {
        ProductDoc saved = indexService.save(doc);
        indexService.refresh();
        return saved;
    }

    @GetMapping("/products/search")
    public List<ProductDoc> search(@RequestParam String title,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "10") int size) {
        return searchService.matchTitle(title, PageRequest.of(page, size))
                .getSearchHits()
                .stream()
                .map(hit -> hit.getContent())
                .toList();
    }
}
