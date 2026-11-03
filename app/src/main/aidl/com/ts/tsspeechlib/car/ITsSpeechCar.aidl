// 車機主程式 com.ts.MainUI 的車身訊號服務介面，從「全景360」反編譯還原（見 docs/head-unit-signals.md）。
// 介面 descriptor 必須是 com.ts.tsspeechlib.car.ITsSpeechCar，所以套件名不可更動。
// AIDL 的 transaction code 由宣告順序決定（從 1 起算），順序一定要跟原始介面一致，
// 否則會呼叫到別的方法：1–16 是空調／天窗／車窗（我們用不到，但必須保留佔位）。
package com.ts.tsspeechlib.car;

interface ITsSpeechCar {
    void onTurnOnAir();                     // 1
    void onTurnDownAir();                   // 2
    void onHighAir();                       // 3
    void onLowAir();                        // 4
    void onSetLoop(String value);           // 5
    void onSetAir(String value);            // 6
    void onUpWindSpeed();                   // 7
    void onDownWindSpeed();                 // 8
    void onWindMode(String value);          // 9
    void onOpenSkylight();                  // 10
    void onCloseSkylight();                 // 11
    void onOpenAllCarwindow();              // 12
    void onCloseAllCarwindow();             // 13
    void onOpenCarwindow(String value);     // 14
    void onCloseCarwindow(String value);    // 15
    void onHeatRearwindow();                // 16

    int GetTotalKilometers();               // 17
    int GetOilLeftover();                   // 18
    int GetLeftTurnSignal();                // 19  1 = 亮
    int GetRightTurnSignal();               // 20  1 = 亮
    int GetEmergency();                     // 21  1 = 雙閃
    int GetSpeed();                         // 22
    int getLineEps();                       // 23  方向盤轉角原始值（±1080）
    int[] GetCarDoorInfo();                 // 24
    int[] GetCarFrRadar();                  // 25  [0..3] 前、[4..7] 後，值為 1–11 等級
    int[] GetCarLrRadar();                  // 26  左右側雷達，長度 < 9 視為無效
    int IsCameraMode();                     // 27  倒車／鏡頭模式
    int GetMcuPowerState();                 // 28  0 = ACC 開
}
