Run
===

compile: mvn compile
run: mvn exec:java


Нагрузочное тестирование
========================

Метод: обращение к nginx через его встроенный прокси-сервер/самописный к странице [wikipedia_russia.html](https://github.com/init/http-test-suite/tree/master/httptest).


> nginx proxy: ab -n 50000 -c 1000 -r
-------------------------------------

Concurrency Level:      1000
Time taken for tests:   16.793 seconds
Complete requests:      50000
Failed requests:        50370
   (Connect: 0, Receive: 370, Length: 49374, Exceptions: 626)
Non-2xx responses:      1
Total transferred:      47154671451 bytes
HTML transferred:       47142525533 bytes
Requests per second:    2977.50 [#/sec] (mean)
Time per request:       335.852 [ms] (mean)
Time per request:       0.336 [ms] (mean, across all concurrent requests)
Transfer rate:          2742249.91 [Kbytes/sec] received

Connection Times (ms)
              min  mean[+/-sd] median   max
Connect:        0   33 263.0      4    3010
Processing:     4  300 130.5    337    1469
Waiting:        0   19  90.3      8    1284
Total:          6  334 311.8    342    4346

Percentage of the requests served within a certain time (ms)
  50%    342
  66%    356
  75%    363
  80%    369
  90%    381
  95%    391
  98%   1036
  99%   1408
 100%   4346 (longest request)


> ProxyServer: ab -n 50000 -c 1000 -r
-------------------------------------

Concurrency Level:      1000
Time taken for tests:   17.851 seconds
Complete requests:      50000
Failed requests:        1586
   (Connect: 0, Receive: 0, Length: 1586, Exceptions: 0)
Total transferred:      47506933129 bytes
HTML transferred:       47494633129 bytes
Requests per second:    2801.01 [#/sec] (mean)
Time per request:       357.014 [ms] (mean)
Time per request:       0.357 [ms] (mean, across all concurrent requests)
Transfer rate:          2598970.79 [Kbytes/sec] received

Connection Times (ms)
              min  mean[+/-sd] median   max
Connect:        0   47 310.8      5    3009
Processing:     2  309 140.7    337    3611
Waiting:        0   16 107.3      6    3277
Total:          2  356 384.7    343    4971

Percentage of the requests served within a certain time (ms)
  50%    343
  66%    350
  75%    354
  80%    358
  90%    371
  95%    382
  98%   1215
  99%   2846
 100%   4971 (longest request)




TODO:

- replace read + write buffers by one ring buffer (?)