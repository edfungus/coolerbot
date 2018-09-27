#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <string>

uint8_t pinPWML = A4; 
int pinLIN1 = 25; 
int pinLIN2 = 26; 

uint8_t pinPWMR = A5;
int pinRIN1 = 27; 
int pinRIN2 = 14; 

BLEServer *pServer = NULL;
BLECharacteristic * pTxCharacteristic;
bool deviceConnected = false;
bool oldDeviceConnected = false;
uint8_t txValue = 0;

#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E" // UART service UUID
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"


class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println("A device has connected!");
    };

    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println("Device has disconnected!");
    }
};

class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      std::string rxValue = pCharacteristic->getValue();
      // yeah not great for performance i know... c_str() does a copy
      int Ld = atoi(rxValue.substr(0,1).c_str());
      int Ls = atoi(rxValue.substr(1,3).c_str());
      int Rd = atoi(rxValue.substr(4,1).c_str());
      int Rs = atoi(rxValue.substr(5,3).c_str());
      switch(Ld) {
        case 0:
          // stop
          digitalWrite(pinLIN1, 0);
          digitalWrite(pinLIN2, 0);
          break;
        case 1:
          // forward
          digitalWrite(pinLIN1, 0);
          digitalWrite(pinLIN2, 1);
          break;
        case 2:
          // backwards
          digitalWrite(pinLIN1, 1);
          digitalWrite(pinLIN2, 0);
          break;
      }
      switch(Rd) {
        case 0:
          // stop
          digitalWrite(pinRIN1, 0);
          digitalWrite(pinRIN2, 0);
          break;
        case 1:
          // forward
          digitalWrite(pinRIN1, 0);
          digitalWrite(pinRIN2, 1);
          break;
        case 2:
          // backwards
          digitalWrite(pinRIN1, 1);
          digitalWrite(pinRIN2, 0);
          break;
      }

      ledcWrite(1, Ls); // Left
      ledcWrite(2, Rs); // Right

      Serial.print(Ld);
      Serial.print(".");
      Serial.print(Ls);
      Serial.print("."); 
      Serial.print(Rd);
      Serial.print(".");
      Serial.print(Rs);
      Serial.println();
    }
};


void setup() {
  Serial.begin(115200);

  pinMode(pinPWML, OUTPUT);
  pinMode(pinLIN1, OUTPUT);
  pinMode(pinLIN2, OUTPUT);
  ledcAttachPin(pinPWML, 1); 
  ledcSetup(1, 12000, 8);
  pinMode(pinPWMR, OUTPUT);
  pinMode(pinRIN1, OUTPUT);
  pinMode(pinRIN2, OUTPUT);
  ledcAttachPin(pinPWMR, 1); 
  ledcSetup(2, 12000, 8);

  // Create the BLE Device
  BLEDevice::init("Carrot Stick");

  // Create the BLE Server
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  // Create the BLE Service
  BLEService *pService = pServer->createService(SERVICE_UUID);

  // Create a BLE Characteristic
  pTxCharacteristic = pService->createCharacteristic(
										CHARACTERISTIC_UUID_TX,
										BLECharacteristic::PROPERTY_NOTIFY
									);
                      
  pTxCharacteristic->addDescriptor(new BLE2902());

  BLECharacteristic * pRxCharacteristic = pService->createCharacteristic(
											 CHARACTERISTIC_UUID_RX,
											BLECharacteristic::PROPERTY_WRITE
										);

  pRxCharacteristic->setCallbacks(new MyCallbacks());

  // Start the service
  pService->start();

  // Start advertising
  pServer->getAdvertising()->start();
  Serial.println("Waiting a client connection to notify...");
}

void loop() {
    // disconnecting
    if (!deviceConnected && oldDeviceConnected) {
        delay(500); // give the bluetooth stack the chance to get things ready
        pServer->startAdvertising(); // restart advertising
        Serial.println("start advertising");
        oldDeviceConnected = deviceConnected;
    }
    // connecting
    if (deviceConnected && !oldDeviceConnected) {
		// do stuff here on connecting
        oldDeviceConnected = deviceConnected;
    }
}

